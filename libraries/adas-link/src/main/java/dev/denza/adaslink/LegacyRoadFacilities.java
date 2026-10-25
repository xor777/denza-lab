package dev.denza.adaslink;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import dev.denza.adaslink.RoadFacilities.*;

/** Historical converter projection, excluded from the product APK source closure. */
final class LegacyRoadFacilities {
    public static View onRoute(Source source,YandexRoute route,RouteConverter.Converted converted,Coordinates.Frame frame) {
        List<Placed> placed=new ArrayList<>();
        if(source!=null)for(Item item:source.items)if(item.mapped()) {
            int edge=Math.min(route.lon.length-2,(int)item.scalar);double fraction=item.scalar-edge;
            double lon=route.lon[edge]+(route.lon[edge+1]-route.lon[edge])*fraction;
            double lat=route.lat[edge]+(route.lat[edge+1]-route.lat[edge])*fraction;
            double[] target=Coordinates.toSdk(lon,lat,frame);
            placed.add(new Placed(item,converted.metres(item.scalar),target[0],target[1]));
        }
        placed.sort(Comparator.comparingDouble(p->p.metres));return new View(source,placed);
    }
    private LegacyRoadFacilities() {}
}
