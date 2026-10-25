package dev.denza.adaslink;

import java.util.ArrayList;
import java.util.List;
import dev.denza.adaslink.LaneGuidance.*;

/** Historical converter placement, excluded from the product APK source closure. */
final class LegacyLaneGuidance {
    static LaneGuidance create(Source source, RouteConverter.Converted c) {
        List<Placed> placed=new ArrayList<>();
        if(source!=null)for(Sign s:source.signs) {
            double at=c.metres(s.scalar); int owner=-1;
            // A sign at an exact link end belongs to that approach link, not the following one.
            for(int i=0;i<c.linkEndScalar.length;i++)
                if(s.scalar>c.linkBeginScalar[i] && s.scalar<=c.linkEndScalar[i]) { owner=i; break; }
            if(owner<0 && s.scalar==0)owner=0;
            if(owner>=0)placed.add(new Placed(s,at,c.linkSegment[owner],c.linkInSegment[owner]));
        }
        return new LaneGuidance(source, placed);
    }
    private LegacyLaneGuidance() {}
}
