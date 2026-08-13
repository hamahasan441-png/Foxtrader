"""Structured logging for the FoxTrader backend.

Installs a console handler on the `fox` logger with a formatter that renders the
structured `extra` fields emitted by `app.middleware.LoggingMiddleware`
(method, path, status, duration_ms, client). Idempotent — safe to call from
`create_app` more than once (e.g. when an app is built for tests and again in
production).
"""

from __future__ import annotations

import logging
import sys

LOGGER_NAME = "fox"

_STRUCTURED_FORMAT = (
    "%(asctime)s %(levelname)s %(name)s "
    "method=%(method)s path=%(path)s status=%(status)s "
    "duration_ms=%(duration_ms)s client=%(client)s"
)


class _StructuredFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        # Fall back to the plain message for records without structured extras.
        fields = ("method", "path", "status", "duration_ms", "client")
        if not all(hasattr(record, f) for f in fields):
            return super().format(record)
        record.asctime = self.formatTime(record, self.datefmt)
        return _STRUCTURED_FORMAT % record.__dict__


def configure_logging(level: int = logging.INFO) -> None:
    logger = logging.getLogger(LOGGER_NAME)
    if logger.handlers:
        return  # already configured

    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(_StructuredFormatter())
    logger.addHandler(handler)
    logger.setLevel(level)
    # Prevent duplicate propagation to the root logger.
    logger.propagate = False
