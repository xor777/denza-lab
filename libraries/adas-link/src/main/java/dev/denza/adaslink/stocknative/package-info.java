/**
 * Source-neutral port of the stock map sender and its mutable state and wire models.
 *
 * <p>Original application: {@code com.byd.launchermap}, BydLaunchermap.apk SHA-256
 * {@code 3a7773d4b41bbbdcb2df7f6f249b91feaf197ff791d67e32e31859bd2425c793}.
 * The local source corpus is {@code captures/hud-firmware-20260923/jadx/BydLaunchermap/sources}.
 * The mappings below keep each original simple class name in this package.
 *
 * <p>Mutable state from {@code com.byd.map.signal.someip.pilot.bean}, {@code classes14.dex}:
 * {@link TrafficDataParam}, {@link NaviInfoParam}, {@link SpeedIntersectionInformationParam},
 * {@link IntersectionLaneInfoParam}, {@link QueryAppointLanesInfoBean}, {@link FontRoundBean},
 * {@link RegionalWeatherParam}, {@link NaviActionAndCameraParam}, {@link GenernalNavigationParam},
 * {@link LightParam}, {@link TunnelParam}, {@link TrafficEventBean}, {@link TrafficLightState},
 * and {@link MixForkInfoParam}.
 * These are state owned by the sender, not upstream input checkpoints. Clock-dependent
 * state uses the explicit {@link StockRuntime} boundary.
 *
 * <p>Wire models from {@code com.byd.noa.plan.data.sdroute}, {@code classes15.dex}:
 * {@link NaviToEHPData}, {@link GuideGroup}, {@link LinkItem}, {@link SegmentItem},
 * {@link GroupEnterCoord}, {@link PntItem}, {@link TrafficLights}, {@link EndPoiInfo},
 * {@link ViaRoadInfoItem}, {@link ViaRoadInfoItemPos}, {@link NaviDataDetail}, and {@link MessageSend}.
 * {@link WireName} retains the original serialized names. Declaration order, numeric
 * types, field visibility, constructor assignments, and getter order are observable
 * parts of the port. Decompiler-generated coordinate field identifiers remain intact.
 * The Chinese numeral helper {@code k.h.a0.d.d} from {@code classes14.dex} is
 * {@link NativeChineseNumbers}; its original field and nested class names remain intact.
 *
 * <p>Migration and source cleanup alone do not establish parity. The independent
 * original application capture supplies actual native getter results, clocks, and
 * scheduler observations; expected packets remain exclusively on the reference side.
 */
package dev.denza.adaslink.stocknative;
