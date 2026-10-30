"""Shared RabbitMQ connection parameters for both consumer threads.

The two workers had identical, separately maintained copies of this. That is
the shape a security setting goes missing in: enabling TLS in one place and not
the other produces a service that looks encrypted and sends half its traffic in
the clear.

Transport security is off by default because the local compose stack has no
certificates, and on wherever `RABBITMQ_SSL` says so. Without it the broker
password crosses the network in an AMQP PLAIN frame, in plaintext, on every
reconnect — which is why the default credentials are also refused outside a
sandbox.
"""

from __future__ import annotations

import logging
import ssl

import pika

import config

logger = logging.getLogger(__name__)

# Credentials that ship with every RabbitMQ installation. Usable locally,
# never in an environment that enforces gateway origin.
_DEFAULT_CREDENTIALS = {("guest", "guest")}


def audit_credentials() -> None:
    """Warns, once at startup, when the broker is reachable with stock credentials."""
    if (config.RABBITMQ_USER, config.RABBITMQ_PASS) in _DEFAULT_CREDENTIALS:
        message = (
            "RabbitMQ is configured with the default guest/guest credentials. "
            "Anyone who can reach the broker can publish forged anomaly events "
            "to the alert service."
        )
        # Treated as a real finding rather than a note when the deployment is
        # otherwise hardened: those two settings disagreeing is the bug.
        if config.GATEWAY_ENFORCE_ORIGIN and config.GATEWAY_INTERNAL_SECRET:
            logger.error("%s Set RABBITMQ_USERNAME and RABBITMQ_PASSWORD.", message)
        else:
            logger.warning("%s Acceptable only in a local sandbox.", message)

    if not config.RABBITMQ_SSL:
        logger.warning(
            "RABBITMQ_SSL is off: the broker password is sent in an AMQP PLAIN frame "
            "in the clear on every connection. Enable it outside a local sandbox."
        )


def connection_parameters() -> pika.ConnectionParameters:
    """Connection parameters for a consumer, with TLS when configured."""
    credentials = pika.PlainCredentials(config.RABBITMQ_USER, config.RABBITMQ_PASS)

    ssl_options = None
    if config.RABBITMQ_SSL:
        # create_default_context verifies the chain and the hostname. Both
        # matter: without hostname checking, any certificate the CA ever issued
        # lets an attacker terminate this connection.
        context = ssl.create_default_context()
        context.check_hostname = True
        context.verify_mode = ssl.CERT_REQUIRED
        ssl_options = pika.SSLOptions(context, server_hostname=config.RABBITMQ_HOST)

    return pika.ConnectionParameters(
        host=config.RABBITMQ_HOST,
        port=config.RABBITMQ_PORT,
        virtual_host=config.RABBITMQ_VHOST,
        credentials=credentials,
        ssl_options=ssl_options,
        heartbeat=30,
        blocked_connection_timeout=60,
    )
