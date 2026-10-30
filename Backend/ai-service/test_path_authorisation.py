"""Path-based authorisation decisions must survive a hostile path.

Three decisions in `security` are made by comparing the request path against a
list: which routes skip gateway authentication, which require an administrator,
and which are admission-controlled. The path comes off the network, so each of
those comparisons is an authorisation check against attacker-controlled input.

Compared raw, prefix matching gets both of the interesting cases wrong:

    /health/../api/ai/train     has an exempt prefix, names the training route
    /api/ai/../ai/train         names the training route, matches no list

The first was measured actually skipping the gateway check. It was not
exploitable, because Starlette does not normalise paths either and the router
answered 404 before any handler ran — but that is the router declining to
cooperate, not the security layer working. A `root_path` mount, a different
ASGI server, or a Starlette release that normalises would each convert it into
unauthenticated replacement of the live model, which is what `POST
/api/ai/train` does.

Two things about how this is tested, both learned by getting them wrong:

  * The middleware is driven **directly**, not through TestClient, because
    httpx normalises the path before sending and would silently repair every
    input this file exists to test.
  * It is constructed **here**, against patched config, rather than reused from
    `main.app`. The middleware reads its secret and its enforcement flag in
    `__init__`, and `main` is imported once per session by whichever test file
    gets there first — so setting environment variables at import time works
    alone and does nothing in a full run. That is what an earlier version of
    this file did, and it passed in isolation while testing nothing.
"""

import asyncio
import logging

import pytest

import config
from security import (
    ADMIN_PATHS, INFERENCE_PATHS, INFRASTRUCTURE_PATHS, INFRASTRUCTURE_PREFIXES,
    GatewayOriginMiddleware, _matches, normalise_path,
)

logging.disable(logging.CRITICAL)

SECRET = "test-gateway-secret-value"

# Every spelling of the training route an attacker might try.
TRAVERSALS = [
    "/health/../api/ai/train",
    "/health/../../api/ai/train",
    "/metrics/../api/ai/train",
    "/docs/../api/ai/train",
    "/redoc/../api/ai/train",
    "/openapi.json/../api/ai/train",
    "/health/api/ai/train",
    "/./api/ai/train",
    "/api/ai/../ai/train",
    "/api//ai//train",
    "//api/ai/train",
    "/healthcheck/../api/ai/train",
]


@pytest.fixture()
def guarded(monkeypatch):
    """The origin middleware, enforcing, wrapped around a stub that records
    whether the request got through."""
    monkeypatch.setattr(config, "GATEWAY_INTERNAL_SECRET", SECRET)
    monkeypatch.setattr(config, "GATEWAY_ENFORCE_ORIGIN", True)

    reached = []

    async def inner(scope, receive, send):
        reached.append(scope["path"])
        await send({"type": "http.response.start", "status": 200, "headers": []})
        await send({"type": "http.response.body", "body": b"{}"})

    middleware = GatewayOriginMiddleware(inner)

    def call(path, headers=()):
        scope = {
            "type": "http", "asgi": {"version": "3.0"}, "http_version": "1.1",
            "method": "POST", "path": path, "raw_path": path.encode(),
            "root_path": "", "scheme": "http", "query_string": b"",
            "headers": [(b"host", b"testserver")] + list(headers),
            "client": ("203.0.113.9", 5555), "server": ("testserver", 80),
        }
        sent = []

        async def receive():
            return {"type": "http.request", "body": b"{}", "more_body": False}

        async def send(message):
            sent.append(message)

        asyncio.run(middleware(scope, receive, send))
        status = next(m["status"] for m in sent if m["type"] == "http.response.start")
        return status

    call.reached = reached
    return call


AUTH = [(b"x-gateway-auth", SECRET.encode())]
ADMIN = AUTH + [(b"x-user-role", b"ADMIN")]
USER = AUTH + [(b"x-user-role", b"USER")]


# ---------------------------------------------------------------------------
# normalise_path
# ---------------------------------------------------------------------------

@pytest.mark.parametrize("raw,expected", [
    ("/health/../api/ai/train", "/api/ai/train"),
    ("/api/ai/../ai/train", "/api/ai/train"),
    ("/./api/ai/train", "/api/ai/train"),
    ("/api//ai//train", "/api/ai/train"),
    ("//api/ai/train", "/api/ai/train"),
    ("/api/ai/train/", "/api/ai/train"),
    ("/health", "/health"),
    ("/health/", "/health"),
    ("/", "/"),
    ("", "/"),
    # More parent segments than there are segments to consume must not escape
    # above the root or raise.
    ("/../../../etc/passwd", "/etc/passwd"),
    ("/..", "/"),
])
def test_normalise_path(raw, expected):
    assert normalise_path(raw) == expected


# ---------------------------------------------------------------------------
# The matching decisions
# ---------------------------------------------------------------------------

@pytest.mark.parametrize("path", TRAVERSALS)
def test_traversal_never_wins_the_infrastructure_exemption(path):
    """A path that resolves outside the infrastructure list is not exempt.

    This is the check that was failing: `/health/../api/ai/train` was exempted
    from gateway authentication on the strength of its first segment.
    """
    if normalise_path(path) in INFRASTRUCTURE_PATHS:
        return
    assert not _matches(path, INFRASTRUCTURE_PATHS, INFRASTRUCTURE_PREFIXES), (
        f"{path} would skip gateway authentication")


@pytest.mark.parametrize("path", TRAVERSALS)
def test_a_path_resolving_to_the_training_route_is_gated_as_one(path):
    if normalise_path(path) != "/api/ai/train":
        return
    assert _matches(path, ADMIN_PATHS), f"{path} bypassed the admin check"
    assert _matches(path, INFERENCE_PATHS), f"{path} bypassed admission control"


def test_docs_subpaths_stay_exempt_but_nothing_else_does():
    """/docs serves /docs/oauth2-redirect beneath it; /health serves nothing.

    Prefix matching is kept only where a route genuinely has children, so a
    route added under /health later is not silently unauthenticated.
    """
    assert _matches("/docs/oauth2-redirect", INFRASTRUCTURE_PATHS, INFRASTRUCTURE_PREFIXES)
    assert not _matches("/health/anything", INFRASTRUCTURE_PATHS, INFRASTRUCTURE_PREFIXES)
    assert not _matches("/metrics/anything", INFRASTRUCTURE_PATHS, INFRASTRUCTURE_PREFIXES)


# ---------------------------------------------------------------------------
# Through the middleware
# ---------------------------------------------------------------------------

@pytest.mark.parametrize("path", TRAVERSALS)
def test_unauthenticated_traversal_is_refused(guarded, path):
    assert guarded(path) == 403, f"{path} skipped gateway authentication"
    assert not guarded.reached, f"{path} reached the handler unauthenticated"


@pytest.mark.parametrize("path", TRAVERSALS)
def test_non_admin_traversal_cannot_reach_the_training_route(guarded, path):
    status = guarded(path, USER)
    if normalise_path(path) == "/api/ai/train":
        assert status == 403, f"{path} let a non-admin train"
        assert not guarded.reached


def test_the_ordinary_path_still_works(guarded):
    """A guard that rejects everything is not a guard."""
    assert guarded("/api/ai/train", ADMIN) == 200
    assert guarded.reached == ["/api/ai/train"]


def test_unauthenticated_and_non_admin_are_both_refused(guarded):
    assert guarded("/api/ai/train") == 403
    assert guarded("/api/ai/train", USER) == 403
    assert not guarded.reached


def test_infrastructure_routes_remain_reachable_without_the_gateway(guarded):
    """Prometheus and the orchestrator do not come through the gateway."""
    for path in ("/health", "/metrics"):
        assert guarded(path) == 200
    assert guarded.reached == ["/health", "/metrics"]


def test_a_wrong_secret_is_refused(guarded):
    assert guarded("/api/ai/predict-anomaly",
                   [(b"x-gateway-auth", SECRET[:-1].encode() + b"X")]) == 403
    assert guarded("/api/ai/predict-anomaly", [(b"x-gateway-auth", b"")]) == 403
    assert not guarded.reached
