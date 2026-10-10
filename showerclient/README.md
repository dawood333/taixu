# Shower Client

This module contains the Shower client integration used by TaiXu. Keep network requests bounded, handle streaming and cancellation explicitly, and avoid logging credentials or sensitive request content.

When changing the client, preserve compatibility with the existing runtime and protocol adapters. Add or update tests for request construction, response parsing, error handling, and cancellation. Validate changes through the relevant module tests and the main application build.
