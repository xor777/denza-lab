import unittest
from acceptance import ar_cache_reused


class ArCacheEvidenceTest(unittest.TestCase):
    @staticmethod
    def sample(route, builds):
        return {'native_route_id': route, 'renderer': {'arGeometryBuilds': builds}}

    def test_prior_routes_do_not_invalidate_reuse_of_the_measured_route(self):
        self.assertTrue(ar_cache_reused(self.sample('selected', 4), self.sample('selected', 4)))

    def test_extra_build_on_the_same_route_is_rejected(self):
        self.assertFalse(ar_cache_reused(self.sample('selected', 4), self.sample('selected', 5)))

    def test_different_route_with_same_counter_is_not_reuse_evidence(self):
        self.assertFalse(ar_cache_reused(self.sample('old', 4), self.sample('new', 4)))

    def test_no_geometry_is_not_cache_evidence(self):
        self.assertFalse(ar_cache_reused(self.sample('selected', 0), self.sample('selected', 0)))


if __name__ == '__main__':
    unittest.main()
