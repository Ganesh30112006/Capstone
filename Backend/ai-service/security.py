"""Request-level hardening for the AI service's HTTP surface.

The platform's Java services each run a `GatewayAuthenticationFilter` that
refuses any request not carrying the gateway's shared secret. This service had
no equivalent, which made it the one component whose port accepted anything
that could reach it — including `POST /api/ai/train`, which overwrites the live
detector. A model-poisoning request needs no credentials to be dangerous; it
only needs to arrive.

Five concerns live here, in the order a request meets them:

  1. **Headers** — the standard response set, applied even to error responses
     produced by the layers below.
  2. **Metrics** — recorded against the matched *route template*, never the raw
     path, because a label fed from the URL is an unbounded label.
  3. **Size** — a bound on the body, enforced as bytes arrive rather than
     trusting the declared Content-Length.
  4. **Origin** — proof the request was proxied by the gateway, compared in
     constant time, plus the role the gateway attested for admin-only routes.
  5. **Admission** — a bound on concurrent model calls, because inference is
     CPU-bound and admitting every arrival makes all of them slower.

These are written as pure ASGI middleware rather than `BaseHTTPMiddleware`
subclasses. That is deliberate on both counts this module exists for: the base
class wraps every request in an anyio task group and an extra pair of streams,
and — more importantly — its `dispatch` runs on the event loop, so a blocking
semaphore acquire there would stall every other connection in the process
rather than just the request waiting for a slot.

All of it is defence in depth behind private networking and the gateway. That
is the point: the gateway already enforces most of this, and the value of
repeating it is precisely that it keeps holding when the gateway is bypassed.
"""

from __future__ import annotations

import asyncio
import hmac
import logging
import re
import time
from typing import Iterable, Optional

from starlette.datastructures import Headers
from starlette.responses import JSONResponse
from starlette.types import ASGIApp, Message, Receive, Scope, Send

import config
import metrics

logger = logging.getLogger(__name__)

# Probed on the private network by the orchestrator and by Prometheus, neither
# of which comes through the gateway. Mirrors the Java filter's exemption of
# /actuator for the same reason.
INFRASTRUCTURE_PATHS = (
    "/health",
    # Listed one by one rather than exempting the whole /health subtree.
    # The prefix rule below exists precisely so that a route mounted under
    # an infrastructure path cannot become unauthenticated by accident, and
    # these two are unauthenticated because that was decided here, not
    # because of where they happen to sit.
    "/health/live",
    "/health/ready",
    "/metrics",
    "/docs",
    "/redoc",
    "/openapi.json",
)

# Of those, only /docs legitimately serves anything beneath itself
# (/docs/oauth2-redirect). The rest are exact endpoints, and matching them by
# prefix would silently exempt anything ever mounted under them — a route added
# at /health/debug would be unauthenticated without anyone deciding that.
INFRASTRUCTURE_PREFIXES = ("/docs",)

# Routes that change the model rather than consult it. The gateway already
# restricts these to admins; repeating the check here is what survives a
# request that never passed through it.
ADMIN_PATHS = ("/api/ai/train",)

# Routes whose cost is a model forward pass, and so are admission-controlled.
INFERENCE_PATHS = (
    "/api/ai/predict-anomaly",
    "/api/ai/anomaly-analysis",
    "/api/ai/forecast",
    "/api/ai/train",
)

# Every request that matched no route collapses to this one label value. It is
# the backstop that makes the metric's cardinality bounded by construction,
# whatever the router reports.
UNMATCHED_ROUTE = "<unmatched>"

SECURITY_HEADERS = (
    (b"x-content-type-options", b"nosniff"),
    (b"x-frame-options", b"DENY"),
    (b"referrer-policy", b"no-referrer"),
    (b"cache-control", b"no-store"),
    # This service returns JSON only; a policy that forbids every subresource
    # costs nothing and blunts any response that is ever rendered as a document.
    (b"content-security-policy", b"default-src 'none'; frame-ancestors 'none'"),
)


def _problem(status: int, error: str, message: str, path: str,
             headers: Optional[dict] = None) -> JSONResponse:
    """The platform's error envelope. Never carries exception detail."""
    return JSONResponse(
        status_code=status,
        content={"success": False, "status": status, "error": error,
                 "message": message, "path": path},
        headers=headers or {},
    )


def normalise_path(path: str) -> str:
    """Resolves `.` and `..` segments and collapses repeated slashes.

    Every path decision in this module — which routes skip gateway
    authentication, which require an admin, which are admission-controlled — is
    made by comparing against the request path, and that path is attacker
    controlled. Compared raw, `/health/../api/ai/train` has the prefix of an
    exempt infrastructure route while naming the one endpoint that replaces the
    live model, and `/api/ai/../ai/train` names the training route while
    matching neither the admin list nor the inference list.

    Measured before this existed, both were true: the exemption fired, and the
    request skipped the gateway check entirely. It was unexploitable only
    because Starlette does not normalise paths either, so the router answered
    404 before the handler was reached — the protection was holding by
    coincidence, not by design. A `root_path` mount, a different ASGI server, or
    a future Starlette that normalises would each turn that coincidence into an
    unauthenticated model replacement.

    Resolving first makes the decision independent of that, and percent-encoded
    traversal (`%2e%2e`, `%2f`) is covered too, because the server decodes into
    `scope["path"]` before this sees it.
    """
    segments: list = []
    for segment in path.split("/"):
        if not segment or segment == ".":
            continue
        if segment == "..":
            if segments:
                segments.pop()
            continue
        segments.append(segment)
    return "/" + "/".join(segments)


def _matches(path: str, routes: Iterable[str],
             prefixes: Iterable[str] = ()) -> bool:
    """Whether the normalised path is one of `routes`, or under one of `prefixes`.

    Exact by default. A route is only matched by prefix when it genuinely
    serves sub-paths, because a prefix rule grants whatever is mounted beneath
    it the same treatment — which for the infrastructure list means skipping
    gateway authentication.
    """
    resolved = normalise_path(path)
    if resolved in routes:
        return True
    return any(resolved.startswith(prefix + "/") for prefix in prefixes)


def route_template(scope: Scope) -> str:
    """The matched route's path template, or a single constant if none matched.

    `scope["route"]` is populated by the router while the request is handled,
    so this is only meaningful after the inner app has run. The fallback is a
    fixed string rather than the request path: the whole reason for reading the
    template is to keep an attacker from minting a new time series per request.
    """
    route = scope.get("route")
    template = getattr(route, "path", None)
    return template if isinstance(template, str) and template else UNMATCHED_ROUTE


class SecurityHeadersMiddleware:
    """Adds the response headers a JSON API should always carry.

    Outermost, so responses generated by the layers below — a 403 from the
    origin check, a 413 from the size cap — carry them too.
    """

    def __init__(self, app: ASGIApp):
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            return await self.app(scope, receive, send)

        async def send_with_headers(message: Message) -> None:
            if message["type"] == "http.response.start":
                headers = message.setdefault("headers", [])
                present = {name.lower() for name, _ in headers}
                headers.extend((n, v) for n, v in SECURITY_HEADERS if n not in present)
            await send(message)

        await self.app(scope, receive, send_with_headers)


class MetricsMiddleware:
    """Records request count and latency against the matched route template."""

    def __init__(self, app: ASGIApp):
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            return await self.app(scope, receive, send)

        status_holder = {"status": 500}

        async def send_wrapper(message: Message) -> None:
            if message["type"] == "http.response.start":
                status_holder["status"] = message["status"]
            await send(message)

        method = scope.get("method", "GET")
        start = time.perf_counter()
        try:
            await self.app(scope, receive, send_wrapper)
        finally:
            # Resolved only now: the router populates scope["route"] while the
            # request is handled, and the template is what keeps this label set
            # finite.
            endpoint = route_template(scope)
            elapsed = time.perf_counter() - start
            metrics.REQUEST_COUNT.labels(method, endpoint, status_holder["status"]).inc()
            metrics.REQUEST_LATENCY.labels(method, endpoint).observe(elapsed)


class BodySizeLimitMiddleware:
    """Caps the request body as it arrives.

    Content-Length is checked first because it costs nothing, but it is only a
    claim. A chunked sender declares no length at all, and a lying one declares
    the wrong length, so the bytes that actually arrive are counted too and the
    request is cut off the moment it passes the cap — rather than after the
    whole thing has been buffered.
    """

    def __init__(self, app: ASGIApp, max_bytes: Optional[int] = None):
        self.app = app
        self.max_bytes = max_bytes if max_bytes is not None else config.MAX_REQUEST_BYTES

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            return await self.app(scope, receive, send)

        headers = Headers(scope=scope)
        declared = headers.get("content-length")
        if declared is not None:
            try:
                if int(declared) > self.max_bytes:
                    return await self._reject(scope, send)
            except ValueError:
                response = _problem(400, "Bad Request", "Malformed Content-Length header.",
                                    scope.get("path", ""))
                return await response(scope, receive, send)

        received = 0
        over_limit = False
        forwarded_start = False
        answered = False

        async def counting_receive() -> Message:
            nonlocal received, over_limit
            message = await receive()
            if message["type"] == "http.request":
                received += len(message.get("body", b""))
                if received > self.max_bytes:
                    over_limit = True
                    # Report the stream as finished, so nothing downstream ever
                    # holds more than the cap. The handler is then working from
                    # a truncated body, and whatever it concludes from that is
                    # not the answer the caller should get — see guarded_send.
                    return {"type": "http.request", "body": b"", "more_body": False}
            return message

        async def guarded_send(message: Message) -> None:
            nonlocal forwarded_start, answered
            if over_limit and not forwarded_start:
                # The handler is about to answer a question it was asked with
                # only part of the request. Its answer is discarded and the
                # real reason is sent instead.
                #
                # Without this, only a caller that declared an honest
                # Content-Length got 413. Omitting the header, or declaring a
                # false length, produced a truncated body, a failed schema
                # validation and a 422 — telling the caller their payload was
                # malformed when it was oversized, and leaving "too large" and
                # "wrong shape" indistinguishable to anyone retrying.
                if message["type"] == "http.response.start":
                    answered = True
                    await self._reject(scope, send)
                return
            if message["type"] == "http.response.start":
                forwarded_start = True
            await send(message)

        await self.app(scope, counting_receive, guarded_send)

        # A handler that answered before reading the oversized part of the body
        # has already had its response forwarded, so there is nothing left to
        # replace and a second response would corrupt the connection.
        if over_limit and not answered and not forwarded_start:
            await self._reject(scope, send)

    async def _reject(self, scope: Scope, send: Send) -> None:
        metrics.SECURITY_REJECTIONS.labels("body_too_large").inc()
        response = _problem(
            413, "Payload Too Large",
            f"Request body exceeds the {self.max_bytes} byte limit.",
            scope.get("path", ""),
        )
        await response(scope, _empty_receive, send)


class GatewayOriginMiddleware:
    """Rejects requests that did not come through the API Gateway."""

    def __init__(self, app: ASGIApp):
        self.app = app
        self.secret = config.GATEWAY_INTERNAL_SECRET
        self.enforce = config.GATEWAY_ENFORCE_ORIGIN
        if self.enforce and not self.secret:
            logger.warning(
                "GATEWAY_INTERNAL_SECRET is not set — direct-access protection is disabled "
                "and POST /api/ai/train will accept unauthenticated model replacement. "
                "Set it in every environment that is not a local sandbox."
            )

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            return await self.app(scope, receive, send)

        path = scope.get("path", "")
        if _matches(path, INFRASTRUCTURE_PATHS, INFRASTRUCTURE_PREFIXES):
            return await self.app(scope, receive, send)

        headers = Headers(scope=scope)

        if not self._from_gateway(headers):
            # Logged without the presented value: echoing a rejected secret into
            # the log moves it somewhere with weaker access control.
            logger.warning("Rejected request to %s that did not come through the API Gateway", path)
            metrics.SECURITY_REJECTIONS.labels("gateway_origin").inc()
            response = _problem(403, "Forbidden",
                                "This service is only reachable through the API Gateway.", path)
            return await response(scope, receive, send)

        if _matches(path, ADMIN_PATHS) and not self._is_admin(headers):
            logger.warning("Rejected non-admin request to %s", path)
            metrics.SECURITY_REJECTIONS.labels("admin_role").inc()
            response = _problem(403, "Forbidden",
                                "This operation requires an administrator.", path)
            return await response(scope, receive, send)

        await self.app(scope, receive, send)

    def _from_gateway(self, headers: Headers) -> bool:
        if not self.enforce or not self.secret:
            return True
        presented = headers.get(config.HEADER_GATEWAY_AUTH)
        if not presented:
            return False
        # Constant-time: a short-circuiting comparison leaks the shared secret's
        # matching prefix through response timing.
        return hmac.compare_digest(presented, self.secret)

    def _is_admin(self, headers: Headers) -> bool:
        if not self.enforce or not self.secret:
            # Without origin enforcement the role header is self-asserted, and
            # checking it would only look like a control.
            return True
        role = (headers.get(config.HEADER_USER_ROLE) or "").strip().upper()
        return role == "ADMIN"


class InferenceAdmissionMiddleware:
    """Bounds concurrent model calls.

    Inference is CPU-bound, and this process already shares its cores with two
    consumer threads and torch's own pool. Admitting every arriving request
    does not increase throughput — it divides the same cores more ways and
    makes every request slower, including the ones that would otherwise have
    finished in time.

    So arrivals wait briefly for a slot and are refused with 503 and
    Retry-After if none frees up. Shedding at the door is what keeps the
    service responsive under a burst instead of collapsing under it, and it is
    what stops a handful of cheap requests becoming unbounded CPU.
    """

    def __init__(self, app: ASGIApp, limit: Optional[int] = None,
                 timeout: Optional[float] = None):
        self.app = app
        self.limit = max(1, limit if limit is not None else config.MAX_CONCURRENT_INFERENCE)
        self.timeout = timeout if timeout is not None else config.INFERENCE_QUEUE_TIMEOUT
        self._semaphore: Optional[asyncio.Semaphore] = None
        self._loop: Optional[asyncio.AbstractEventLoop] = None

    def _slots(self) -> asyncio.Semaphore:
        """Bound to the running loop, and rebuilt if that loop changes.

        A semaphore created at import time carries waiter futures belonging to
        whichever loop first touched it; under a test client that starts a fresh
        loop per call, reusing it would await futures that will never complete.
        """
        loop = asyncio.get_running_loop()
        if self._semaphore is None or self._loop is not loop:
            self._semaphore = asyncio.Semaphore(self.limit)
            self._loop = loop
        return self._semaphore

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http" or not _matches(scope.get("path", ""), INFERENCE_PATHS):
            return await self.app(scope, receive, send)

        slots = self._slots()
        loop = asyncio.get_running_loop()
        waited_from = loop.time()
        try:
            await asyncio.wait_for(slots.acquire(), timeout=self.timeout)
        except (asyncio.TimeoutError, TimeoutError):
            metrics.SECURITY_REJECTIONS.labels("inference_overload").inc()
            metrics.INFERENCE_REJECTED.inc()
            logger.warning("Shedding %s: all %d inference slots busy for %.1fs",
                           scope.get("path", ""), self.limit, self.timeout)
            response = _problem(
                503, "Service Unavailable",
                "The inference queue is saturated; retry shortly.",
                scope.get("path", ""),
                headers={"Retry-After": str(max(1, int(self.timeout)))},
            )
            return await response(scope, receive, send)

        metrics.INFERENCE_QUEUE_WAIT.observe(loop.time() - waited_from)
        metrics.INFERENCE_IN_FLIGHT.inc()
        try:
            await self.app(scope, receive, send)
        finally:
            metrics.INFERENCE_IN_FLIGHT.dec()
            slots.release()


async def _empty_receive() -> Message:  # pragma: no cover - trivial
    return {"type": "http.request", "body": b"", "more_body": False}


# Device identifiers arriving over the broker become dictionary keys in the
# per-device tables both workers hold, values in published events, and text in
# log lines. Constrained to the same shape device-service accepts, so a
# malformed one cannot abuse any of the three.
DEVICE_UID_PATTERN = re.compile(r"^[A-Za-z0-9._:-]{1,%d}$" % config.MAX_DEVICE_UID_LENGTH)


def valid_device_uid(value) -> bool:
    """Whether an identifier off the network is safe to key state and logs on."""
    if not isinstance(value, str) or not DEVICE_UID_PATTERN.match(value):
        metrics.INVALID_READINGS.labels("device_uid").inc()
        return False
    return True


def scrub(value, limit: int = 64) -> str:
    """Makes an untrusted string safe to put in a log line.

    Device identifiers arrive from the network and end up in logs. A newline in
    one forges a log entry; a megabyte of text floods the log. Both are cheap to
    send and neither should be possible.
    """
    text = str(value) if value is not None else "<none>"
    cleaned = "".join(char if char.isprintable() else "?" for char in text)
    return cleaned[:limit] + ("…" if len(cleaned) > limit else "")
