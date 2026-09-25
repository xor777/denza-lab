"""Production firmware identity; independent of tests and emulator fixtures.

The complete original text stays in the isolated image. Its imported APIs are
provided only by the explicit runtime bridge; unsupported APIs terminate it.
"""

EXPECTED = '9e36cdbf841d54a3b1ea3631b5d5b867d5908bd191a113f53b5bb4182df82eb9'


def need(value, reason):
    if not value:
        raise ValueError(reason)
