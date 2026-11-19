import unittest
from acceptance import ar_cache_reused, cancellation_cleared


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


class CancellationEvidenceTest(unittest.TestCase):
    @staticmethod
    def report(blanks, roads, active, stops, session, frames):
        return dict(blanks=blanks, roads=roads, active=active, stops=stops, session=session, frames=frames)

    def test_an_active_route_cancelled_with_three_new_blacks_an_inactive_road_and_a_stop_passes(self):
        before = self.report(3, 100, True, 1, True, 500)
        after = self.report(6, 103, False, 2, False, 503)
        self.assertTrue(cancellation_cleared(before, after, after))

    def test_blacks_left_from_an_earlier_disable_are_not_cancellation_evidence(self):
        # The old runner: the disable already sent three blacks and stopped; cancelling an
        # inactive session sends nothing, and an absolute count of three still passed.
        before = self.report(3, 100, False, 1, False, 500)
        self.assertFalse(cancellation_cleared(before, before, before))

    def test_a_cancellation_that_sends_no_new_blacks_fails(self):
        before = self.report(3, 100, True, 1, True, 500)
        after = self.report(3, 101, False, 2, False, 500)
        self.assertFalse(cancellation_cleared(before, after, after))

    def test_a_road_left_active_or_a_session_left_open_fails(self):
        before = self.report(3, 100, True, 1, True, 500)
        self.assertFalse(cancellation_cleared(before, self.report(6, 103, True, 2, False, 503),
                                              self.report(6, 103, True, 2, False, 503)))
        self.assertFalse(cancellation_cleared(before, self.report(6, 103, False, 1, True, 503),
                                              self.report(6, 103, False, 1, True, 503)))

    def test_output_that_goes_on_after_the_stop_fails(self):
        before = self.report(3, 100, True, 1, True, 500)
        after = self.report(6, 103, False, 2, False, 503)
        self.assertFalse(cancellation_cleared(before, after, self.report(6, 104, False, 2, False, 504)))


if __name__ == '__main__':
    unittest.main()
