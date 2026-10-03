# Investigation of 2026-09-22 (history)

Part of [Telematics findings](README.md). Moved verbatim from `docs/telematics-findings.md` on 2026-10-03; "above" and "below" in the text refer to that file's order (see [Pages](README.md#pages)).

## Contents

- [Vehicle investigation and authorized APN test, 2026-09-22](#vehicle-investigation-and-authorized-apn-test-2026-09-22)
  - [Registration failure located in a full keepalive cycle](#registration-failure-located-in-a-full-keepalive-cycle)
  - [Authorized public-path test: endpoint selection works, connection does not](#authorized-public-path-test-endpoint-selection-works-connection-does-not)
  - [Resolver follow-up: fast local failure, still no identified DNS cause](#resolver-follow-up-fast-local-failure-still-no-identified-dns-cause)
  - [MikroTik observation: SSH works, packet capture is disabled by device-mode](#mikrotik-observation-ssh-works-packet-capture-is-disabled-by-device-mode)
  - [No-reboot DNS observation: controls pass, native lookups do not enter the router resolver](#no-reboot-dns-observation-controls-pass-native-lookups-do-not-enter-the-router-resolver)
  - [Direct resolver controls and Wi-Fi notification path, 16:29–16:38 MSK](#direct-resolver-controls-and-wi-fi-notification-path-16291638-msk)
  - [Wi-Fi notification replay delivered, registration still fails, 16:46–16:49 MSK](#wi-fi-notification-replay-delivered-registration-still-fails-16461649-msk)
  - [What the older Dolphin binary can establish](#what-the-older-dolphin-binary-can-establish)
  - [Dolphin comparison: registration, session selection and token delivery](#dolphin-comparison-registration-session-selection-and-token-delivery)
  - [Follow-up without the full firmware archive](#follow-up-without-the-full-firmware-archive)
  - [Wi-Fi and registration](#wi-fi-and-registration)
  - [Sidecar/API and firmware limits](#sidecarapi-and-firmware-limits)
- [Earlier phone and vehicle observations](#earlier-phone-and-vehicle-observations)
- [Subject](#subject)
- [Phone link: healthy (measured 2026-09-22)](#phone-link-healthy-measured-2026-09-22)
- [What the app reports about the vehicle](#what-the-app-reports-about-the-vehicle)
- [Onboard SIM: no service (measured 2026-09-22)](#onboard-sim-no-service-measured-2026-09-22)
- [Internet sockets do not identify a working telematics channel](#internet-sockets-do-not-identify-a-working-telematics-channel)
- [Live test: data + roaming on, no change (2026-09-22, owner-authorised)](#live-test-data--roaming-on-no-change-2026-09-22-owner-authorised)
- [Where the car's remote switches live (found 2026-09-22)](#where-the-cars-remote-switches-live-found-2026-09-22)
- [Remote Location refuses to turn on: owner-account gate (2026-09-22)](#remote-location-refuses-to-turn-on-owner-account-gate-2026-09-22)
- [Owner in the cloud, refused on the car (2026-09-22)](#owner-in-the-cloud-refused-on-the-car-2026-09-22)
- [The car's SIM is not real-name registered (2026-09-22)](#the-cars-sim-is-not-real-name-registered-2026-09-22)
- [Why Wi-Fi seemed unable to carry the telematics link (2026-09-22, superseded)](#why-wi-fi-seemed-unable-to-carry-the-telematics-link-2026-09-22-superseded)
- [The private APN, and why a Chinese roaming SIM restores everything (2026-09-22)](#the-private-apn-and-why-a-chinese-roaming-sim-restores-everything-2026-09-22)
- [Historical private-endpoint investigation (2026-09-22)](#historical-private-endpoint-investigation-2026-09-22)
- [Public MQTT broker is reachable; private registration path is not (2026-09-22, later pass)](#public-mqtt-broker-is-reachable-private-registration-path-is-not-2026-09-22-later-pass)
- [Stock virtual SIM and the roaming reject (2026-09-22, later pass)](#stock-virtual-sim-and-the-roaming-reject-2026-09-22-later-pass)
- [Not established yet (2026-09-22, superseded)](#not-established-yet-2026-09-22-superseded)

## Vehicle investigation and authorized APN test, 2026-09-22

> **Superseded 2026-09-23:** the public-branch failure was the native pre-DNS gate, which `notify_nw(4)` under `double_apn` opens; the stock client then registered, logged in and got its token over Wi-Fi — see [firmware-reading.md, Current-build network gate, now established in code](firmware-reading.md#current-build-network-gate-now-established-in-code) and [cloud-tile.md, Stock-client Wi-Fi adaptation, 2026-09-23](cloud-tile.md#stock-client-wi-fi-adaptation-2026-09-23).

The requested outcome is **sidecar → Denza cloud → official Denza phone app**.
An independent dashboard is not the target. The owner confirms a master account
and successful sign-in on the vehicle; repeat account-login checks are not a
remaining task.

| Channel | Measured result | Limit of the result |
| --- | --- | --- |
| IVI Internet | Validated default Wi-Fi network; TCP to `emqx-cn.denzacloud.com:8884` succeeds | Does not prove MQTT authentication or ingestion |
| Native `cloudmanager` | `getTCPStatus()` returns 0. `triple_apn` selects `dilinkterminalreg-cn.iov.denza.cloud` with APN1 unavailable. An authorized `double_apn` test selects public `dilinkreg-cn.denzacloud.com`, but still fails obtaining its IP | The public branch exists; neither profile established registration in the observed cycles |
| Separate native `mqttserv` | `isConnected()` returns 0 | Does not identify its selected broker or failure reason |
| Android `CloudServiceApp` | Repeated failure obtaining `cloudToken.dat`, followed by rejection of an empty/short token before MQTT connect | This app suppresses the original exception; independent `AutoiotService` evidence below identifies a missing-file error, but not its cause or the phone feed's owner |

The two native reads used Binder transactions 7 and 8 respectively, after
checking both Stub and Proxy in this vehicle's `framework.jar`. No register,
publish, network-notification or configuration transaction was called.
`persist.sys.cloud.app_reg_status=0` and `persist.sys.cloud.token_flag=0`;
the Android app's `sys.cloudserviceapp.mqtt` property is empty. Empty properties
are not zero-valued error codes.

### Registration failure located in a full keepalive cycle

At **14:30:06 on 2026-09-22**, a passive 265-second capture covered the stock
250-second keepalive timer. Unlike the earlier short captures, it caught the
current native `cloudmanager` (PID 113) explaining its reconnect failure:

```text
registDomain is dilinkreg-cn.denzacloud.com
addrDomain is dilinkaddr-cn.denzacloud.com
apn_type is triple_apn
domain is dilinkterminalreg-cn.iov.denza.cloud
mApn1Connected IS 0
domain prasr faile
addr = 211: _200:
tcpReconnect,but can not parse domian ip,mParseCount:0;
```

This is direct evidence from the installed Denza build, not the older Dolphin
comparison. **The observed reconnect stops at endpoint resolution in the
`triple_apn` branch, with APN1 unavailable.** It does not reach a registration
response in this cycle. The missing token is a separate confirmed downstream
prerequisite failure; this trace makes unsuccessful registration a likely
explanation for it, but does not prove when or why the token file disappeared.

Independent read-only checks immediately afterwards:

| Domain | Car Wi-Fi | Google and Cloudflare public DNS |
| --- | --- | --- |
| `dilinkterminalreg-cn.iov.denza.cloud` | `unknown host` | NXDOMAIN, DNS status 3 |
| `dilinkreg-cn.denzacloud.com` | Resolves to `139.159.228.68`; ICMP reply in 321 ms | Same A record |
| `dilinkaddr-cn.denzacloud.com` | Resolves to `113.45.216.69`; ICMP reply in 249 ms | Same A record |

Thus general Internet access is present, but the observed native registration
branch selects an address unavailable in the tested public DNS views and
reports no APN1 connection. The internal-looking domain and APN selection are
consistent with a carrier-private path; reachability from the intended carrier
network was not measured. The native log does not reveal whether it actually
issued a DNS query or returned early because APN1 was absent.

The two public names are real candidates, not interchangeable replacements
established by this test. ICMP proves host reachability only; service ports,
protocol compatibility, authentication and the phone's main feed remain
unverified. The subsequent authorized profile test below confirms selection
of the public registration name, but not a working connection over Wi-Fi.
No hostname mapping was changed. A generic geographic proxy is not a
demonstrated repair.

The installed `ClientConfigurationService` additionally confirms that
`cloud_server` and `cloudservice_enable` configuration messages are forwarded
to native `setControlConfigure()`. Its `sys.cfg_client_ready=1` is assigned
during local initialization, so it is **not** evidence of downloaded cloud
configuration or completed registration. Its diagnostic `sys.tcp_step` is
currently empty and supplies no extra error code. None of these configuration
methods was invoked.

Selected capture and DNS evidence, the installed configuration APK hash and
source pointers are in ignored `captures/telematics-20260922/token-cause/`.
The detailed report is `token-cause-findings.md` beside that folder.

### Authorized public-path test: endpoint selection works, connection does not

The installed BYD wireless diagnostic tool has real `double_apn` and
`triple_apn` radio buttons (`Tab_repair2`, `tab_repair2.xml`). Its
`sendSwitchApnPolicy()` sends the stock `RADIO_CONFIG` action with operation
`set_default_data` and the chosen `apn_type`. Current hashes of both the tool
APK and `byd-telephony-common.jar` match the analyzed local copies.

The current implementation traces the complete Java-side transition:

1. `BydGsmCdmaPhone.handleDefaultDataConfig()` calls
   `BydDcTracker.onMultiApnTypeChange()`.
2. Selecting `double_apn` sets `persist.sys.byd.apn_type`, deactivates the
   dedicated APN1 connection if present, sets its disable flag to 1, clears
   APN1 network properties, and marks APN1 unsupported. The current APN1 CID
   and interface are already empty. The examined handler does not disable
   Wi-Fi or toggle general mobile data.
3. For the default-data phone, it sends `SIM_AND_APN_TYPE_CHANGE`.
   `CloudServiceApp.AppsReceiver` converts `double_apn` to an internal native
   notification with `APPID=0`, `cmd=4`, `data.apn_type=0`; `triple_apn` maps to
   1. It forwards this through the registered native listener.
4. The phone implementation also switches marker files in `/collect2/vsim/`.
   `BydNetworkUtils.initApnPolicy()` reads those markers before the build
   default at startup. This is a persistent policy change, not a temporary
   UI selection. The stock reverse transition to `triple_apn` exists.

The owner explicitly approved one bounded profile test, including possible
automatic registration by the stock client and return to `triple_apn`.
At **15:23:10 MSK**, the stock `RADIO_CONFIG` operation was delivered once
to `com.android.phone`. Radio logs and properties confirmed `double_apn` and
APN1 disable=1. No active APN1 bearer existed (`cid=-1`). Wi-Fi routes remained
unchanged immediately after the switch.

During 270 seconds of passive observation, the **15:24:16** stock keepalive
cycle logged:

```text
apn_type is double_apn
getSSLIPByDomainName is dilinkreg-cn.denzacloud.com
tcpReconnect,but can not parse domian ip,mParseCount:0;
```

Thus this exact installed native client **does select the public registration
branch**. It still failed obtaining an address in the observed cycle. At
15:25:28–29, ordinary car-shell DNS and one ICMP request per hostname succeeded:
`dilinkreg-cn.denzacloud.com` → `139.159.228.68` (291 ms), and
`dilinkaddr-cn.denzacloud.com` → `113.45.216.69` (227 ms). Default network 100
remained validated Wi-Fi. This narrows the unresolved problem to the native
client's resolution/network-selection path; it does not identify a specific
resolver fault, prove a DNS packet was sent, or establish protocol compatibility.

Both native connection getters remained 0 at each 45-second sample through
270 seconds; registration and token flags stayed 0, and the Android MQTT
property stayed empty. No successful registration was observed. This test
does not establish a usable sidecar-to-Denza upload path or an updated phone
status card.

At **15:27:44**, the same stock operation returned `triple_apn`. Radio,
CloudServiceApp and native logs confirm receipt and return to the original
private-domain/APN1 branch. At 15:27:49, every sampled property matched its
baseline, APN1 disable=0 and ADB was available. The Wi-Fi route was preserved;
network 100 was still validated at 15:28:18. Incidental multicast route entries
on the internal Ethernet interface differed, so the full routing dump is not
byte-identical. No service restart, APK install, manual token operation or
hostname override was used. As disclosed before approval, marker-file contents
were inaccessible and their exact original state cannot be certified.

One host log reader stopped on invalid UTF-8 from native output. A replacement
reader decoded invalid bytes safely without repeating the profile switch; it
captured the decisive public keepalive cycle and rollback. The immediate
native reaction to the first switch was not fully recovered. Consequently,
absence of other traffic from this selected log is not evidence that no
server exchange occurred anywhere in the interval.

The plan, result and selected evidence are in ignored
`captures/telematics-20260922/public-path-test-plan.md`,
`public-path-test-findings.md`, and `public-path-test/`.

**Next read-only lead:** the readable current `/system/lib64/libbyddns.so`
(94,040 bytes, SHA-256
`360d8351d2efa0b2896028941f8dd8151a21780d3b444187dd06327f7d862050`)
exports separate APN DNS routines. Its `android_gethostbynamefor_spec_dns()`
selects APN1 DNS, optionally binds c-ares to `net.lte.apn1.ifname`, and uses
`persist.radio.dns.query.mode` (default `1`). That proves an APN-specific
resolver implementation exists in this firmware, **not** that the observed
`getSSLIPByDomainName` calls it. The exact current caller and failed return
condition remain to be located before proposing any DNS or routing change.

### Resolver follow-up: fast local failure, still no identified DNS cause

> **Superseded 2026-09-23:** the cause is not DNS: `getSSLIPByDomainName` returns before any lookup while gate byte `0x35e` is 0 — see [firmware-reading.md, Current-build network gate, now established in code](firmware-reading.md#current-build-network-gate-now-established-in-code).

After the owner asked to continue, a second bounded test used the same stock
profile transition and restoration, with a complete redacted text stream from
native PID 113 across all logcat tags and an additional `c_ares_dns` filter.
The readers handled invalid UTF-8 without stopping. No log-level property,
resolver setting or routing rule was changed.

At **15:41:40**, this captured the immediate Java-to-native notification and
the next steps: `send211`, preparation of FuncID 211 (101 bytes), public
`getSSLIPByDomainName`, empty address fields (`211: _200:`), then
`send211,but can not parse domian ip`. Packet contents were redacted before
saving. A prepared packet is not proof of transmission. The subsequent
`clear542Cfg clear542inFile fail!` is recorded without assigning a cause or
claiming the inaccessible configuration file's state was restored.

The normal keepalive at **15:45:06.898** again printed the public hostname;
the failure followed at **15:45:06.899**. This approximately 1 ms log interval
is consistent with an early local check, cached result or asynchronous resolver;
it is not a measured DNS round-trip or proof that no query was issued. No
`c_ares_dns` explanation appeared. The exact resolver/caller remains unknown.

Independent checks narrowed, but did not solve, that gap:

- Route lookup for the public registration IP with UID 0 and UID 2000 both
  selected Wi-Fi table 1040. A process-specific socket bind/mark is not ruled
  out by ordinary route lookup.
- The current `libbydpolicydns.so` implements source/domain/APN and fallback
  rules. It contains none of the three exact registration-domain literals;
  generic, wildcard and dynamic rules remain possible. Its involvement in
  this failure was not established.
- Readable `libmosssl.so`, `libmoscurl.so` and `libnetd_client.so` did not
  identify `getSSLIPByDomainName`. The name alone does not establish a TLS
  handshake failure. A selected recent audit/crash window showed no matching
  denial, but this is not complete historical or permission proof.
- `netd` diagnostics returned `FAILED_TRANSACTION`; `NetMonitorService`
  returned no dump. No `tcpdump`/`tshark` is available in the shell PATH.

The second 270-second observation again showed connection getters and
registration/token flags at 0. At **15:46:13** the profile was restored; the
15:46:19 snapshot matched every sampled baseline property, with ADB available.
A subsequent check confirmed validated default Wi-Fi network 100. Both host
log readers finished. No restart, installation or manual token operation was
performed.

The next useful independent measurement is a bounded DNS/connection trace at
the owner's Wi-Fi access point, correlated with native timestamps. Availability
of an administered router versus phone hotspot has been asked of the owner;
no router access or packet capture was attempted pending that information.
Changing DNS or constructing a generic proxy is not yet an evidence-backed fix.
Detailed evidence and limits are in ignored
`captures/telematics-20260922/resolver-followup/findings.md` and
`resolver-trace-test/`.

### MikroTik observation: SSH works, packet capture is disabled by device-mode

At 15:52–16:01 MSK the owner's RB5009UG+S+ (`192.168.88.1`, RouterOS
7.19.5 stable) accepted SSH as `admin` using an existing key. DHCP and ARP
confirmed the car at `192.168.88.109`. Car-only connection tracking showed
DNS request/reply pairs with the router, multiple established internet TCP
sessions, and an unanswered `SYN_SENT` flow to `10.167.206.17:9105`.
This confirms the private-address attempt reaches the home router; it does
not identify the sending process or establish why the remote path fails.
The shared DNS cache contained several Denza service names, but no selected
registration names in the snapshots. A shared cache snapshot cannot prove
that a particular client or process did not issue a query.

The proposed DNS-only capture did **not** run. Although sniffer configuration
was accepted, `start` left `running=false`; the router's log records
`script error: not allowed by device-mode` at 15:57:54. The effective
`/system device-mode print` is `mode=home`, `sniffer=no`, `flagged=no`.
The PCAP contains only its 24-byte header, zero packets. Absence of a native
DNS query cannot be inferred from it. The host observer was stopped at
15:59:15 before its APN-switch step, so there was no third `double_apn` test.

All five temporary sniffer fields were restored exactly at 15:59:17; the
one empty capture file was retrieved and subsequently removed from the
router. At 16:01, its absence, stopped sniffer and continuing router uptime
were checked. All 16 sampled car properties match baseline, including
`triple_apn` and APN1 disable `0`; ADB remains `device`. No router DNS,
firewall, routing, device-mode, or offload setting was changed. The host
harness now checks both device-mode and `running` before any car test.

The next capture requires `/system device-mode update sniffer=yes`, changing
only that capability. Official MikroTik documentation requires physical
confirmation and says the router reboots after confirmation. This would
interrupt the existing transport, so the update has **not** been issued;
the owner subsequently ruled out a router reboot because it would interrupt
other work. That constraint remains in force; the device-mode update is not
a pending action. A DNS-service log alternative was subsequently verified without
reboot (see the next section). Evidence and the bounded test plan are in
`captures/telematics-20260922/mikrotik/`. Reference:
[MikroTik device-mode](https://help.mikrotik.com/docs/spaces/ROS/pages/93749258/Device-mode).

### No-reboot DNS observation: controls pass, native lookups do not enter the router resolver

The owner ruled out a router reboot because it would interrupt other work.
At 16:07–16:15 MSK a temporary, separate 1000-entry memory logger successfully
recorded only selected registration/control DNS names. The effective topic
filter is `dns,!packet,!raw`, not `dns,debug`: this RouterOS build emits its
query/done events without the debug topic. A unique control lookup from
`192.168.88.109` was visible before the native test began. No device-mode,
DNS, firewall, route, offload, sniffer or tunnel change was needed.

The existing bounded stock test observed a triple_apn failure at 16:10:06,
then double_apn from 16:10:12 through 16:14:46. Both its immediate native
attempt and its 16:14:16 keepalive selected `dilinkreg-cn.denzacloud.com` but
failed obtaining its address; TCP/MQTT and registration/token flags remained
0. None of the selected registration names entered the router's DNS-service
journal during those attempts. The journal's seven entries did not fill its
buffer. All 16 sampled properties matched baseline after stock rollback.

Separate ordinary lookups **after rollback**, at 16:14:52–53, were recorded
as queries from the car. The router returned `139.159.228.68` for the public
registration name and `113.45.216.69` for the public address name; ICMP to
both answered. The private registration lookup was recorded but returned
unknown host to the caller; the selected text log does not establish rcode.
Thus the home router's ordinary DNS can resolve the current public names,
while the native service is taking a different failing path.

This is not a full packet capture. Thirty-five car-only connection snapshots
also show direct DNS to `114.114.114.114`; queries sent there are outside the
router resolver journal. Early local failure, another resolver, cached state
or another bound network are still possible. Absence of all native DNS
traffic, a specific failed resolver function, and a proxy/DNS fix are not
established. The observed `10.167.206.17:9105` flow still does not identify a
process.

A supporting static check of current `libcares.so` (version string 1.17.1)
found that `ares_set_servers_csv` returns success immediately for an empty
string, without replacing its initialized server list. Empty APN DNS
properties therefore do not alone prove that no resolver is configured.
Its actual use by cloudmanager's failing function remains unproven.

The temporary logger and action were removed at 16:14:55; original logging
configuration matched exactly, router uptime continued, and car profile/
ADB were restored. Evidence: `captures/telematics-20260922/mikrotik/`
`dns-log-test-3/findings.md` and neighboring logs. The network investigation
can continue without reboot using this validated DNS journal, sampled
connections and the local firmware corpus. Full packet visibility would
require a separately available capture point or a later maintenance window.

### Direct resolver controls and Wi-Fi notification path, 16:29–16:38 MSK

After the owner confirmed that SSH worked again, the investigation resumed
without changing router configuration or repeating the APN transition.

The current framework provides a Wi-Fi notification path: ordinary
`CONNECTIVITY_CHANGE` reads the supplied `NetworkInfo`, falling back to
`getActiveNetworkInfo()`, and maps CONNECTED to state **2**. The callback in
`BYDConnectManager` forwards it through `BYDTCPConnectService.notify_nw()` to
native `cloudmanager`. Dedicated APN1 uses state **1**. These are distinct
states; their presence in code does not prove which state the current native
process remembered. The selected recent logs and service dumps did not expose
that internal value. No network notification was manually sent.

Current `libcares.so` disassembly confirms Android resolver-list initialization,
fallback to `net.dns1` through `net.dns8`, and ultimately `127.0.0.1` when no
server is configured. All eight properties were empty in the read-only sample.
However, **loopback DNS is working on this car**: root-owned UDP/53 listeners
exist on IPv4/IPv6 loopback and internal interfaces, and `dnsmasq` PID 2314 is
running. Its command line includes `--no-resolv` and `--listen-mark 0xf0063`.
Socket UID and process presence alone do not prove socket-to-PID ownership.

Direct UDP DNS requests from the existing ADB shell returned matching query
IDs, rcode 0 and `139.159.228.68` for `dilinkreg-cn.denzacloud.com` through
each of `127.0.0.1`, `192.168.88.1` and `114.114.114.114`. Observed elapsed
times were approximately 45, 50 and 260 ms, including ADB/command overhead;
these are not isolated wire latencies. Consequently, neither empty global DNS
properties nor a missing loopback resolver explains the failure on its own.
This does not establish the resolver, socket mark, UID policy or early return
condition used by the native public-registration function.

Two additional direct loopback controls at 16:38 returned
`113.45.216.69` for the public address name and rcode **3 / NXDOMAIN** for
`dilinkterminalreg-cn.iov.denza.cloud`. The local resolver therefore answers
both successful and negative queries; it is not simply an unresponsive port.

The first raw-query harness produced no output even for the router control;
it is invalid evidence of DNS failure. Holding stdin open with `adb shell -T`
produced the validated responses. The bounded host command was then ended;
its cleanup exit code is not a DNS result. No diagnostic `nc` process remained
in the subsequent process check.

The older Dolphin comparison has an APN1-connected check before its resolver
call, but lacks the current `getSSLIPByDomainName` implementation. Its state
layout or acceptance of network notifications must not be assumed for this
Denza build. Readable current IPC libraries did not supply the missing
implementation. The next precise static target remains the current native
function and its caller; the installed executable is unreadable to this shell,
and a matching extractable firmware image is not yet available.

> **Superseded 2026-09-23:** the matching OTA was decoded and `cloudmanager` recovered — see [firmware-reading.md, Matching archive decoded and cloud client recovered, 2026-09-23](firmware-reading.md#matching-archive-decoded-and-cloud-client-recovered-2026-09-23).

The Android app also contains stock energy-statistics upload code
(`EnergyUploadService`, `NOTIFY_ENERGY_RANKING`), gated on MQTT connection.
Its fields cover consumption and mileage statistics; this does not establish
ownership of the phone's main SOC/range/online card. The previously identified
`CloudControllerManager.publishMqttMessage()` still requires an existing MQTT
connection and restricts this implementation to the supported watch channel;
it is not an established generic vehicle-status upload API.

At 16:37:14 the profile remained `triple_apn`, APN1 disable=0, both connection
getters and registration/token flags were 0, and ADB was `device`. No car
setting, service, installed app, token, route or router setting changed in
this continuation. Evidence is in ignored
`captures/telematics-20260922/resolver-search/`.

The subsequent owner-approved test below replayed only the true Wi-Fi-connected
state **2** to the native service, during a bounded public-profile window with
stock rollback. The exact plan and limits are recorded in that evidence
directory as `wifi-notification-test-plan.md`. State **1** was not sent to
represent an APN1 connection that does not exist.

### Wi-Fi notification replay delivered, registration still fails, 16:46–16:49 MSK

The owner approved proceeding after the explicit state-changing test request.
Before the test, Wi-Fi network 100 was the validated default, profile was
`triple_apn`, APN1 disable=0, and both native connection getters were 0.
The existing ADB transport and tunnel were retained.

At **16:46:06.225**, the stock profile operation reached native cloudmanager
and selected `double_apn`. Its immediate public registration attempt again
failed to obtain an address. At **16:46:09.382** the host sent exactly one
`service call cloudmanager 1 i32 2`, verified against the current framework's
Stub and Proxy. The CLI returned `Parcel(NULL)` without an error; delivery is
established independently by native PID 113 logging at **16:46:09.680**:

```text
notify_nw()  state = 2
persist.sys.byd.apn_type is double_apn
```

At **16:47:37.010**, the framework's normal 250-second keepalive timer fired.
The native attempt at **16:47:37.014** again logged the public registration
name, `getSSLIPByDomainName`, empty `211`/`200` address fields, and
`tcpReconnect,but can not parse domian ip,mParseCount:0;`. All four observation
samples (15, 60, 105 and 150 seconds from test start) showed TCP/MQTT getters
and registration/token flags at 0, with Wi-Fi still validated.

**Result:** simply replaying the framework's actual Wi-Fi-connected state does
not repair the observed public-registration failure. This rules out a missing
single notification as a sufficient explanation/remedy in this test. It does
not prove how the native implementation handled state 2 internally, rule out
all initialization/state-ordering problems, or identify the exact failing
condition in `getSSLIPByDomainName`. No successful registration or phone-card
update was observed. The prior direct DNS controls remain separate evidence;
they do not establish this process's resolver path.

Once the keepalive failure and subsequent status samples were captured, the
host harness was intentionally ended through its SIGTERM/finally path, before
the maximum test duration. Stock rollback began at **16:49:15.166**, and native
logs confirm `triple_apn` at **16:49:15.494**. At **16:49:18.641**, all **16**
sampled properties matched baseline; APN1 disable=0, validated Wi-Fi network
100 and ADB `device` were confirmed. The public-profile window was about
189 seconds. The harness's exit 130 records deliberate host interruption;
rollback completion is established by its separate final snapshot. The
independent deadline guard exited after verified restoration and did not
need to issue a command. No test log reader or guard remained afterwards.

No Wi-Fi toggle, router change, restart, install, manual telemetry publication
or false APN1 notification was used. As before, inaccessible marker-file
contents and the prior internal cached network state are not byte-for-byte
verified. The evidence is in ignored
`captures/telematics-20260922/wifi-notification-test/`, including the exact
executed host-script hash and restoration comparison. The reusable host
diagnostic is [wifi_notification_test.py](../../tools/telematics/wifi_notification_test.py);
its guard subsequently changed to a relative delay for portability across
macOS Python versions, and payload filtering was tightened without another
vehicle run.

### What the older Dolphin binary can establish

The available comparison remains useful without waiting for a full Denza OTA.
It is the ARM64 `cloudmanager` from
[the Dolphin research corpus](https://github.com/wheregoes/byd-dolphin-hacking),
pinned at commit `d2677796660cc685faaa3ba2d078e6846ce4c1b1`, version string
`di3_6125f_mp0712_dev.202505070`, SHA-256
`25fcfbcf4b81346ac5d5861308fc0577b7dfa484d8c9ae9227e65e27c2b5648d`.
The artifact hash was rechecked. Static analysis of this binary establishes:

- **Notification handling:** the routine beginning at `0x284e0`, identified
  by its `notify_nw()` log, branches only for state `1` and state `-2`.
  State `2` reaches the epilogue at `0x286b8` without setting its connectivity
  fields. State `1` stores 1 in the adjacent fields at object offsets `0x19d`
  and `0x19e`; the APN1-disconnected branch clears `0x19e`. This is a real
  example of a delivered ordinary-network notification doing no useful work
  for this native client.
- **Resolution gate:** the function at `0x2bdc0` checks the byte at `0x19e`
  and returns false if it is zero (`0x2bde8–0x2bdec`). Only after passing that
  check can it call `android_gethostbynamefor_spec_dns()` at `0x2be20`.
  Thus a caller can report address-resolution failure without issuing DNS.
- **Error propagation:** the endpoint routine at `0x28810` resolves the
  registration and address-service names through this gated function and
  returns a readiness flag. The `send211` path tests that result at
  `0x28078–0x2807c` and prints its generic domain/IP error at `0x28170` on
  failure. The endpoint routine uses the old global BYD domains; these are
  not the Denza public names measured on the current car.
- **Token provisioning:** the previously traced incoming-message branch 507
  writes `cloudToken.dat` and then sets the token flag. This supports examining
  the registration-to-token sequence as well as network selection.

These are code facts about the comparison artifact. The current Denza logs
show `getSSLIPByDomainName` and public/private profile selection that are not
the same recovered implementation. The old binary therefore supplies a
concrete hypothesis and a function map, not proof of the current native gate,
field offsets or a working replacement client. Sending state `1` merely to
make a flag true is not a justified next step: the APN1 branch also invokes
other initialization/vehicle-side work, while this car has no connected APN1.
No comparative binary was executed and no new car operation was performed
during this static follow-up.

A readable copy of the current Denza executable is needed to verify its
code-specific behavior; a full OTA is one possible source, not a prerequisite
for comparative analysis. Evidence pointers are saved in ignored
`captures/telematics-20260922/dolphin-comparison/`.

### Dolphin comparison: registration, session selection and token delivery

The subsequent static pass recovered the following bootstrap sequence in the
same pinned Dolphin binary. Function names below are inferred from strings,
callers and behavior; the executable is stripped. Addresses belong only to
that artifact.

```text
VIN and SIM identifiers available
  → resolve registration/address-service endpoints
  → 211: register terminal
  → 200: obtain working server IP and port
  → 221: log in to that server
  → 507: request token if token_flag != 1
  → save cloudToken.dat and set token_flag = 1
```

| Stage | Static evidence | Scope of the conclusion |
| --- | --- | --- |
| Registration inputs | `send211` checks VIN; packet builder `0x3a164` checks cached IMSI and ICCID and returns on empty values (`0x3a1f8`) | Available SIM identity and cellular network registration are separate prerequisites. This does not establish missing identifiers on the Denza |
| Registration response | Dispatcher stores the 211 response status at `0x2f588`; handler at `0x2d48c` treats status 1 as success and advances to function 200 (`0x2d688`), or an internal queue branch | The token file is not a prerequisite for constructing this registration request |
| Server selection | Response 200 supplies IPv4 address and port (`0x2fb48–0x2fba8`); `0x2fd6c` passes them to `setServerInfo`, then `0x2fd7c–0x2fd88` queues function 221 | The initial registration hostname is not necessarily the destination of the working session |
| Login success | Function discriminator is 221 at `0x2f560`; status 1 sets the native connected field (`0x2f888`) and calls post-login work (`0x2f908 → 0x2e538`) | Some nearby text logs say 220; the recovered discriminator is 221 |
| Token request | Post-login work reads `persist.sys.cloud.token_flag` at `0x2e634`; unless its value is 1, it prepares and queues function 507 (`0x2e688–0x2e6b8`) | In this implementation token acquisition follows successful native login |
| Token storage | Incoming 507 branch validates a nonzero length below 129, passes the returned bytes to the file writer (`0x30684`) and sets the flag only if the writer succeeds (`0x306f8`) | Merely creating a file or setting a flag would not establish receipt of a server-issued token |

The current Denza Android `CloudServiceApp` independently reads that same
file through `CloudServiceApp.lambda$mqttInit$0`, using
`ConfigConstants.SECURITY_KEY_FILE`. Its MQTT connection code rejects an
empty/short token before connecting. The missing file was already confirmed
by the current `Autoiot` exception. Together these observations support
**failed native bootstrap as an explanation for the absent MQTT token**;
they do not prove that the current native executable retains the complete
Dolphin sequence or explain the file's historical disappearance. There is no
demonstrated need to supply a token merely to begin terminal registration.

The old native sender also performs APN-specific routing outside the low-level
socket routine. `addIPRoute` at `0x3e534` reads
`net.lte.apn1.ifname`, builds a host-route request, and calls `0x3f690`.
That helper assembles an `ip route add` operation using the APN1 interface and
table 10 (`0x3f71c–0x3f784`); an empty interface prevents that operation. Three
call sites in the send dispatcher are at `0x3e24c`, `0x3e27c` and `0x3e2b0`.
**Those callers do not check its return value before continuing**, so this is
evidence of intended APN routing, not proof that a missing route alone makes
the old client's TCP connection impossible. The inspected socket-creation
sequence (`0x49328–0x494a0`) contains no explicit interface bind before
`connect`; that does not rule out routing policy or process-wide selection.
The current Denza public branch may use different routing code.

One initially promising telemetry string was narrowed rather than promoted
to a result: `send state of charge` at `0x33b18` belongs to a routine receiving
`mChargingStatus`. It maps input 1 to status 5 and other values to 6, and queues
function 502 only when the native session is connected and its configuration
flag is enabled. Its packet builder (`0x3af80`) includes a status and timestamp.
This is a charging-state event, **not evidence of a battery-percentage upload
or ownership of the official app's main vehicle card**.

For the requested sidecar, this distinguishes two designs. A network helper
would need the stock client to reach endpoint resolution, use a working route
and complete server-selected login/token delivery. A replacement uploader
would additionally need the current protocol and the actual vehicle-card
feed; the old packet map does not establish either. A router proxy cannot
change an internal client gate that prevents a request from being issued.
Whether such a gate explains the Denza public-path failure remains a
hypothesis: its captured error still occurs at `getSSLIPByDomainName`, before
the later stages mapped here.

This follow-up used existing files only. No vehicle or router operation,
registration request, token access or telemetry publication was performed.
Selected instruction ranges and the stage map are saved in
`bootstrap-routing.asm` and `bootstrap-routing.json` alongside the earlier
comparison evidence.

### Follow-up without the full firmware archive

The following records the preceding investigation; the full-cycle capture
above resolves its previously unknown native endpoint and immediate failure.

**A specific missing-file error is now established.** At 14:13:27 and 14:13:32
on 2026-09-22, running `com.byd.autoiot.service` reported
`java.nio.file.NoSuchFileException: /data/system/cloud/cloudToken.dat`.
Its installed APK's `VehicleUtil.getAskToken()` (`y3.p.a()` in the decompilation)
opens that exact path using `Files.newInputStream`, catches the real exception,
logs it and returns an empty string. Thus this is a missing-path error as seen
by that process, rather than an inferred token-format error. Combined with
`CloudServiceApp`'s empty-token failure, it narrows the immediate prerequisite
failure to availability of the shared vehicle token. It does not establish why
the file is absent, when it disappeared, or which current component must create
it. Owner/master-account sign-in is a separate, already confirmed fact.

`Autoiot` also contains a platform-dependent token request by broadcast
(`APPID=15`, `cmd=2`). Current `CloudServiceApp.AppsReceiver` forwards these
requests through `CloudServiceImp` to its native listener. This is not a
demonstrated independent provisioning path; no broadcast was sent.

**The failure has history.** The installed `BydWirelessTools` exposes a readable
diagnostic provider at `content://com.byd.wirelesstools/net_status`. A projection
limited to network fields and an aggregate returned 2632 records, spanning
2026-08-11 17:25:44 to 2026-09-22 14:05:33, with no `mqtt_status=1` or
`apn1_status='connected'` record. In its current code, `mqtt_status` is 1 only
when `mqttserv.isConnected()` returns 1; a missing service or IPC failure also
leaves it at 0. This establishes no recorded success, not continuous outage
between samples or absence of earlier reports. **The same table's `tcp_status`
is a latency-test classification (`tcpAvgDelay`), not `cloudmanager` status.**
Do not use its zero values as historical native-cloud TCP measurements.

**Profile selection remains partly opaque.** The current configuration is
`triple_apn`; APN1's disable flag is 0, but APN1 and APN3 are disconnected.
`persist.radio.net.lte.apn3.onwifi=0` and `net.apn3.wifi=wifienable` coexist.
In current `BydDcTracker`, that flag value routes a Wi-Fi event to
`setActiveStateForApn((byte) 0, true)`; it is not proof that Wi-Fi is forbidden.
The APN1 routing table 10 is empty. Packaged MQTT files contain public and
private addresses plus environment flags, but no observed selector establishes
which one the native client currently uses. A short passive log capture did
not reveal a registration response or identify the selected endpoint.

**A separate HTTPS telemetry path exists, but is not a verified replacement.**
Installed `com.byd.CanDataCollect` has Denza production defaults under
`https://newcan-cn.denzacloud.com:9999/can-api/api/dataCollected/` for collection
configuration and file uploads. Its ordinary connected-network callback has
no mobile-only gate, and the examined configuration request reads vehicle/SIM
identity without reading `cloudToken.dat`. It uploads configured CAN batches;
no link to the official phone's SOC/online card was established. The current
test-mode property is empty, but a private dynamic configuration can override
the default URLs, so the actual active destination remains unverified.

The default host resolved to `113.45.51.1` on two public resolvers. Bounded TCP
connects to port 9999 timed out from both the car and Mac; the shell route from
the car was through `wlan0`. No application request or telemetry was sent.
These observations identify another channel to study, not a working ingestion
route or proof of a geographic restriction.

At that stage the current native registration endpoint was unknown; the
full-cycle capture above subsequently identified it. The token producer's
current implementation and the public-path selection rules remain unresolved.
The full firmware archive was not needed for these measurements, and the
opaque archive by itself does not guarantee readable implementation code.

### Wi-Fi and registration

In the current `services.jar`, `BYDMultiApnConnReceiver` forwards an ordinary
connected network as code 2, without a mobile-only gate in that branch.
`BYDConnectManager` and `BYDTCPConnectService.notify_nw()` pass this to the
native client. Therefore the earlier assertion that the stock client cannot
receive Wi-Fi connectivity events is unsupported. The later full-cycle trace
shows that its observed registration branch still selects `triple_apn` and
fails to obtain its selected endpoint while APN1 is disconnected.

Dedicated APN1/spec has separate modem-registration gates in `BydDcTracker`.
The Android modem is OUT_OF_SERVICE and APN1/2/3 were disconnected. This does
not prove every Denza cloud operation requires a carrier-private APN.

`CloudServiceApp` names the production broker
`ssl://emqx-cn.denzacloud.com:8884`. Car TCP reachability and Mac-side verified
TLS were measured separately; car-side MQTT authentication was not attempted.
Other IVI domains (`apr-cn.denzacloud.com`, `idilink-cn.denzacloud.com`,
`dilink-mqtt-cn.denzacloud.com`) resolved to `1.1.1.1` on the car and independent
public resolvers. Firmware contains public and private MQTT profiles, but
the active choice has not been established. A general proxy is not yet an
evidence-backed repair.

### Sidecar/API and firmware limits

> **Superseded 2026-09-23:** a readable `cloudmanager` was recovered from the decoded OTA, and a status upload reached the phone over Wi-Fi — see [firmware-reading.md, Matching archive decoded and cloud client recovered, 2026-09-23](firmware-reading.md#matching-archive-decoded-and-cloud-client-recovered-2026-09-23) and [cloud-protocol.md, Real SOC reached the official phone app over Wi-Fi, 2026-09-23](cloud-protocol.md#real-soc-reached-the-official-phone-app-over-wi-fi-2026-09-23).

The current APK's `CloudControllerManager.publishMqttMessage()` requires an
already connected MQTT client, accepts only AppID DMS 101, and implements the
`V/1/V2W/` watch-message branch. It is not a verified SOC/status upload API.
The APK also has an energy-ranking uploader; its relation to the phone's
main status card is unknown. Phone APK strings name status queries such as
`getCurrentStateByVin` and `latestVehicleCondition`, but no supported upload
contract was recovered from their names alone.

The installed build is `34.1.33.2605218.1`, fingerprint
`BYD-AUTO/IVI/IVI:13/TP1A.220624.014/eng.build20260705.011226:user/release-keys`.
A [public firmware package](https://modhub24.com/firmware/firmware_41d3df51)
contains matching plaintext metadata. Only byte ranges were fetched; the
whole-package signature was not verified. The outer ZIP is readable, but its
`Android/Target/android.zip` and `Config.xml` are opaque binary data. No
readable copy of the current native `cloudmanager` was obtained. The installed
updater's Java IVI path delegates installation to recovery, not an accessible
Java unpacker; it was inspected statically and never invoked.

An older public Dolphin `cloudmanager` links a received token message with
writing `cloudToken.dat`. That is comparative evidence only: it is not the
current Denza protocol. The native trace now identifies the selected endpoint
and immediate resolution failure. The public-profile selection and current
token-provisioning implementation still need evidence. No working sidecar
ingestion path, hardware-replacement requirement, or need to visit China has
been established.

Local evidence and detailed reports are under ignored
`captures/telematics-20260922/`: `read-only-findings.md`,
`registration-findings.md`, `alternative-paths-findings.md`,
`server-selection-findings.md`, `token-cause-findings.md`, and their selected
evidence folders.
No tokens, VINs, raw captures or extracted binaries belong
in tracked documentation. The investigation described in this section did
not change vehicle settings or services and preserved the existing ADB tunnel.

## Earlier phone and vehicle observations

The following retains the earlier session's observations. Broad connectivity
inferences are qualified against the direct client measurements above.

## Subject

| Item | Value |
| --- | --- |
| Phone | OPPO Find X9 Ultra (`CPH2841`), Android 16 |
| App | `com.byd.aeri.caranywhere` 9.16.1 (`versionCode` 561), installed 2026-09-20 |
| Flavor | Denza — launch activity is `…splash.activity.DenzaLaunchActivity` |
| Bound vehicle | 腾势Z9GT 易三方 |

## Phone link: healthy (measured 2026-09-22)

Nothing on the phone explains the offline state.

- Default network is Wi-Fi, `VALIDATED`. No VPN, `private_dns_mode` is
  `null`, Data Saver off, and the app has no background or network
  restriction (`RUN_IN_BACKGROUND` allow, standby bucket 10,
  `Restrict background: false`).
- The app reached the cloud at least once: it was installed two days before
  this session, is signed in, and renders the bound vehicle's model name and a
  server-side seasonal skin.
- The selected phone-app endpoints below resolve and complete TLS from the same
  network. `403` on a bare root path is the server answering; it does not prove
  a successful authenticated API request.

  | Host | DNS | `https://host/` |
  | --- | --- | --- |
  | `cache.denzacloud.com` | 221.194.141.166 | 403 |
  | `service8.denzacloud.com` | 116.205.162.231 | 200 |
  | `i.tengshiauto.com` | 221.194.141.166 | 200 |
  | `cache.bydauto.com.cn` | 36.41.168.166 | 403 |
  | `profilesys.bydauto.com.cn` | 221.194.141.166 | 200 |

  Endpoints were read out of `classes.dex` (403 distinct hosts; the Denza set
  is `denzacloud.com` / `tengshiauto.com`, the shared BYD set is
  `bydauto.com.cn` / `bydoceanauto.com`).

## What the app reports about the vehicle

Home screen, 2026-09-22 12:41:

- Range, charge and the second gauge all read `--`; `已熄火` (powered down);
  the vehicle-signal chip carries an `×`; `更新于: --`.
- Bluetooth key `未激活` (not activated); NFC and UWB greyed out.
- A card states `车机端"远程位置"未开` — *"remote location" is not enabled on
  the head unit*.
- Pulling to refresh raises the toast `获取车况信息失败` — *failed to obtain
  vehicle condition information*. Its wording is about vehicle status, not
  about the network, which the app words differently (`网络连接失败`).

**Correction.** The home screen's `更新于: --` was read in this session as
"the cloud has never held a status report". That is wrong. The vehicle detail
page, one swipe down, shows

> `更新于: 07/22 18:33`

so the cloud does hold a last report, dated **22 July**, two months before
this session. The car reported and then stopped; it is not a car the cloud
has never met. The empty home-screen field is a blank summary, not a
never-seen marker. Everything below that depended on "never reported" is
corrected accordingly.

The account, the binding and the vehicle identity are fine: the cloud knows
the car and holds a two-month-old report for it.

## Onboard SIM: no service (measured 2026-09-22)

> **Superseded 2026-09-23:** the SIM is still out of service (read again on 2026-09-23), but it is no longer the car's cloud link: the stock client registers and uploads over Wi-Fi — see [cloud-tile.md, Stock-client Wi-Fi adaptation, 2026-09-23](cloud-tile.md#stock-client-wi-fi-adaptation-2026-09-23) and [stock-client-boundaries.md, Cellular data retention during ACC-off, 2026-09-23](stock-client-boundaries.md#cellular-data-retention-during-acc-off-2026-09-23).

Read from the head unit over ADB (`127.0.0.1:5555`, `DiLink5_1`), read-only.

- The car carries a **China Mobile** SIM: `gsm.sim.operator.numeric` `46013`,
  `CMCC`, `iso-country` `cn`, IMSI `4601389963…`, ICCID `898608…`. The owner
  confirms it is the stock SIM.
- Android reports `isEmbedded=false`, `simSlotIndex=0`. This alone does not
  establish physical accessibility, replaceability or compatibility with a
  different SIM.
- It is registered nowhere. `mVoiceRegState=1(OUT_OF_SERVICE)`,
  `mDataRegState=1(OUT_OF_SERVICE)`, `mCellIdentity=null`, signal level 0,
  `gsm.operator.alpha` empty, `carrierName=Emergency` — constant across the
  whole log window. `gsm.operator.iso-country` reads `ru`.
- Both switches are off as well: `mobile_data=0`, `data_roaming=0`
  (`airplane_mode_on=0`). Registration fails before the data toggle matters,
  but a roaming attempt cannot succeed while these stay off.

## Internet sockets do not identify a working telematics channel

An earlier reading concluded that stock telematics cannot use the head unit's
Wi-Fi. That conclusion is withdrawn. The sockets below prove Internet activity,
but their endpoint provider and UID do not prove successful Denza telemetry.

- The head unit's default network is Wi-Fi, validated, and from the car
  `ping service8.denzacloud.com` answers in 217 ms.
- `/proc/net/tcp` on the car shows **established** connections to Huawei
  Cloud China (`HWCSNET`, the same provider that hosts `service8.denzacloud.com`):

  | Peer | Port | Socket UID |
  | --- | --- | --- |
  | `122.9.108.196` (`ecs-…hwclouds-dns.com`) | 443 | 1000 (`system`) |
  | `110.41.149.112` (`ecs-…hwclouds-dns.com`) | 30023 | 0 (`root`) |

- Running daemons include `cloudmanager`, `cloudctrlserv` and `mqttserv`.
  Attribution of the listed sockets to one of them was not established.
  `netDCL` provides conntrack observations. The later native log sample did
  contain periodic CAN-status messages, but no registration exchange.

The head unit has Internet access. The direct status getters in the latest
section are stronger evidence for the specific cloud clients than these
unattributed sockets.

## Live test: data + roaming on, no change (2026-09-22, owner-authorised)

`svc data enable` and `data_roaming=1` on the head unit, polled for two
minutes: `OUT_OF_SERVICE` throughout, operator still empty. The radio log
gives the reason it cannot be a roaming-permission question alone —
`regState = NOT_REG_MT_NOT_SEARCHING_OP`, `reasonForDenial = NONE`,
`cellIdentity` empty: the modem is not searching, not being refused.
`isManualNetworkSelection=false`, `cell_on=1`, `preferred_network_mode=32`.
Both switches were returned to `0` afterwards. The owner states the SIM does
not work in Russia, which closes this line.

## Where the car's remote switches live (found 2026-09-22)

The app's complaint `车机端"远程位置"未开` points at a stock setting. It is not
in any Android settings namespace; it belongs to `com.byd.carsettings`
(`/system/priv-app/CarSettingPlatform/CarSettingPlatform.apk`, which carries
the strings `Remote Location` ×11 and the key `remote_location` ×8). The
head unit's UI is in English.

Reached through the settings app's own search (`com.byd.search.SearchActivity`
— type a term, press Enter), which maps the three remote entries:

| Entry | Path |
| --- | --- |
| Remote Unlock | Vehicle → Locks |
| **Remote Location** | **System → Connect** |
| Remote Monitor | System → Connect |

State of **System → Connect** as found:

- **Remote Location — OFF.** Its own description scopes it narrowly: *"When
  turned on, you can view the vehicle's location information on the mobile
  app"*. On that wording it governs the location card, not the vehicle-status
  report, so enabling it is not expected to end the offline state.
- **Remote Monitor — OFF** (*"view real-time vehicle footage through the
  mobile app"*).
- **Smartphone and Vehicle Connectivity — Not connected.**
- **Cellular Data — OFF**; Wi-Fi ON (`sweet-home`), Bluetooth ON, Hotspot OFF.

`com.byd.systemsettings.privacy.PrivacyModeActivity` starts and immediately
finishes; `PrivacyManagerActivity` opens a dialog holding only Microphone,
Interior Camera, Location Service (all `12 months`) and Personalized
Recommendation — none of them the remote switch.

## Remote Location refuses to turn on: owner-account gate (2026-09-22)

Owner-authorised attempt to enable **System → Connect → Remote Location**.
Four taps on the switch (2560×1600 panel, `input tap 1762 930`); the switch
stayed `OFF` every time. Injected taps do reach this app — the same method
navigated from the settings search into this page — so the refusal is the
app's, not a missed coordinate.

The reason is a toast from `com.android.systemui` (uid 10067,
`appop=TOAST_WINDOW`), too short-lived for a host-side `screencap` to catch.
Taking the shot **on the car** (`input tap …; sleep 0.6; screencap -p
/sdcard/…`) caught it:

> **This setting can only be modified by the owner's account**

The head unit is signed in: Account Center → Personal Information shows a
profile (`Dmitry`, with photo, gender and birthday filled in). So the gate is
not "nobody is logged in". Either this account is not the vehicle's **owner**
in BYD's cloud — the usual state for an imported car whose original account
still holds ownership — or the ownership check needs a cloud round-trip that
does not complete here.

Nothing on the car was changed: the switch never moved.

**Useful technique.** For any short-lived toast on this head unit, screenshot
from inside the device in the same shell command as the tap. A host-side
`exec-out screencap` loses the race; `dumpsys window windows | grep Toast`
confirms a toast exists but never carries its text.

## Owner in the cloud, refused on the car (2026-09-22)

> **Superseded 2026-09-23:** the explanation below assumed the head unit hears from the cloud only over its suspended SIM; since 2026-09-23 the stock client registers and holds TCP over Wi-Fi. Whether the owner-only gate has cleared since has not been rechecked — see the open questions in [README](README.md#current-state).

The owner states the car was imported from China and that ownership was
transferred to their account through a representative. The app agrees.

`我的` → the vehicle card → **`车辆授权`** (vehicle authorisation) opens a page
offering **`新建授权`** — *create a new authorisation* — over the bound
`腾势Z9GT` (VIN ending `147747`), with `暂无有效授权` ("no valid
authorisations") below. Granting authorisations to other people is an owner's
function; a secondary user is not offered it.

So the cloud holds this account as the vehicle's owner, while the head unit
refuses owner-only settings for the same account. The two sides disagree, and
the head unit is the stale one. This supersedes the earlier guess that the
account was merely a secondary user.

## The car's SIM is not real-name registered (2026-09-22)

`我的` → **`SIM卡实名`** (carrying a red action badge) opens `我的车联网卡`
("my connected-car SIM"):

- the bound `腾势Z9GT易三方插混`, VIN ending `147747`
- a China Mobile connected-car number `1489963****` (the `148` range is
  China Mobile's IoT block)
- status **`未实名认证`** — *not real-name verified* — with `前往认证`
  ("go and verify")

Mainland carriers suspend a SIM that is not real-name registered. This is a
sufficient, independent cause for the vehicle's cellular link dying, and it
sits well with a last cloud report on 22 July rather than at the moment the
car left China.

Identifiers are masked here on purpose: this repository is published on
GitHub. The full VIN and SIM number are visible in the app.

**How the two findings join.** Ownership moved in the cloud. The head unit
learns such things from the cloud over the telematics link. That link runs on
a SIM the carrier has suspended, so the car never received the change and
still gates owner-only settings against its last known owner. This is the
leading explanation, not a proven chain: the car does hold Wi-Fi sockets to
the same cloud, and nothing yet shows that ownership sync refuses to ride
them.

> **Superseded 2026-09-23:** the premise that the cloud link runs only on the suspended SIM no longer holds: the stock client rides Wi-Fi — see [cloud-tile.md, Stock-client Wi-Fi adaptation, 2026-09-23](cloud-tile.md#stock-client-wi-fi-adaptation-2026-09-23). Whether ownership then syncs is unverified.

## Why Wi-Fi seemed unable to carry the telematics link (2026-09-22, superseded)

> **Superseded 2026-09-23:** Wi-Fi carries it. The placeholder names (`apr-cn`, `idilink-cn` → `1.1.1.1`) are not what the client needs: under `double_apn` it uses the public `dilinkreg-cn.denzacloud.com:6001`, `dilinkaddr-cn.denzacloud.com:6021` and the returned `dilinknat0-cn.denzacloud.com:6041`, and registration, login, token and SOC upload all ran over Wi-Fi — see [cloud-protocol.md, Official-cloud registration and login over Wi-Fi verified, 2026-09-23](cloud-protocol.md#official-cloud-registration-and-login-over-wi-fi-verified-2026-09-23) and [cloud-tile.md, Stock-client Wi-Fi adaptation, 2026-09-23](cloud-tile.md#stock-client-wi-fi-adaptation-2026-09-23).

The car names its own cloud in system properties:

| Property | Value |
| --- | --- |
| `persist.service.host.name` | `apr-cn.denzacloud.com` |
| `persist.service.apn3.host.name` | `idilink-cn.denzacloud.com` |
| `persist.sys.cloud.app_reg_status` | `0` |
| `persist.sys.cloud.token_flag` | `0` |

The second name is bound to **APN3** — a dedicated cellular access point, and
`dumpsys netstats` on the car keeps separate `APN1`/`APN3` interface groups.

Both telematics names resolve to **`1.1.1.1`**, a placeholder — from this
network, and from the car itself:

```
apr-cn.denzacloud.com.      300 IN A 1.1.1.1
idilink-cn.denzacloud.com.  300 IN A 1.1.1.1
```

The answer comes from the zone's own authority (`ns1.huaweicloud-dns.*`), so
it is deliberate, not a local resolver artefact. An ordinary app host such as
`service8.denzacloud.com` resolves normally (`116.205.162.231`) from the same
car at the same moment.

**Conclusion.** The vehicle's telematics servers have no public address. They
are served inside the carrier's private APN (split-horizon DNS), so the link
cannot ride the head unit's Wi-Fi: there is nothing on the public internet to
connect to. `app_reg_status=0` and `token_flag=0` say the car's cloud client
never completed registration, which is what one expects when its endpoint has
been unreachable since the SIM lost service.

This also dims the "put a local SIM in slot 0" idea: a local carrier gives
general internet, not China Mobile's private APN, so the telematics endpoint
stays unreachable. A working route back is the original SIM with service **and**
roaming — home-routed roaming keeps APN traffic tunnelled to the home network
— or a BYD-side public endpoint, which nothing here suggests exists.

The established Wi-Fi sockets to Huawei Cloud (`122.9.108.196:443`,
`110.41.149.112:30023`) therefore belong to other services; `CloudServiceApp`
itself ships no hardcoded endpoints (its dex carries none), taking them from
these properties instead.

## The private APN, and why a Chinese roaming SIM restores everything (2026-09-22)

The "no way back" reading of the previous section was too strong. The owner
reports that importers in Russia restore the master account, the app and the
Bluetooth key with a Chinese roaming SIM ("rSIM") in place of the factory one.
The car's own APN table explains exactly why that works.

`/system/etc/apns-conf.xml` is readable by shell (the `telephony` provider is
not: `SecurityException: No permission to access APN settings`). It carries
BYD-specific entries:

```
<apn carrier="CMCC SPEC" apn="CMIOTBYDNSA.GD" mcc="460" mnc="…"
     type="spec" protocol="IPV4V6" roaming_protocol="IPV4V6" />
<apn carrier="CMIOTBYDNSA.GD" apn="" mcc="460" mnc="…"
     type="ia"   protocol="IPV4V6" roaming_protocol="IPV4V6" />
<apn carrier="CMCC FUN"  apn="CMMTMBYDNSA.GD" mcc="460" mnc="…" type="fun" />
```

`CMIOT` is China Mobile's IoT arm and `BYDNSA.GD` is a private access point
provisioned for BYD in Guangdong. Both the initial-attach (`ia`) and the
special (`spec`) entries exist for **every** China Mobile MNC the car may see
— `00`, `02`, `04`, `07`, `08` and `13` — and `13` is the factory SIM's own
network (IMSI `46013…`). `roaming_protocol` is set, so roaming on these APNs
is anticipated by the configuration, not an afterthought.

**Mechanism.** A China Mobile SIM provisioned for `CMIOTBYDNSA.GD` attaches
that APN abroad through home-routed roaming: the bearer is tunnelled back to
the Chinese core, so the car sits inside the carrier's private network while
parked in Russia. `idilink-cn.denzacloud.com` then resolves to a real address
instead of the public `1.1.1.1` placeholder, the cloud client can register
(`app_reg_status`, `token_flag`), the pending ownership change reaches the head
unit, and the owner-only gates — Remote Location, the Bluetooth key's
`未激活` — clear on their own.

**What this does and does not change.** A *local* (Russian) SIM still cannot
help: it offers general internet, not this private APN. What is needed is a
*Chinese* SIM carrying this APN with international roaming — either the
factory SIM restored to service, or a replacement M2M SIM from the same
provisioning. The earlier conclusion stands only for the Wi-Fi path.

> **Superseded 2026-09-24:** no Chinese SIM is needed for the cloud link: the public profile works over Wi-Fi with no cellular service, and a forum car reportedly connected over Wi-Fi after its rSIM was removed. Forum cars on local SIMs reach TLS and send 211 over mobile data but get code 3 — see [cloud-tile.md, Reported Wi-Fi success after SIM removal; native identity cache, 2026-09-24](cloud-tile.md#reported-wi-fi-success-after-sim-removal-native-identity-cache-2026-09-24) and [cloud-tile.md, Build-58 follow-up: fresh native registration replies with code 3, 2026-09-24](cloud-tile.md#build-58-follow-up-fresh-native-registration-replies-with-code-3-2026-09-24).

## Historical private-endpoint investigation (2026-09-22)

> **Superseded 2026-09-23:** the stock client needs no address inside the private APN: the public endpoints carry registration, login and SOC upload over Wi-Fi, so the note below that no upload path is demonstrated is itself out of date — see [cloud-protocol.md, Real SOC reached the official phone app over Wi-Fi, 2026-09-23](cloud-protocol.md#real-soc-reached-the-official-phone-app-over-wi-fi-2026-09-23).

**Superseded conclusion:** the early claims below that every software route
was closed or that the private SIM was necessarily required are not established.
Later current-build evidence above found a selectable public registration
branch. That branch still fails locally, and no working upload path has been
demonstrated; neither success nor impossibility follows from these older DNS
observations alone.

Tested, because a placeholder answer is often just split-horizon DNS keyed on
the asking resolver. It is not.

Queried over DNS-over-HTTPS from resolvers inside China — AliDNS
(`dns.alidns.com`) and Tencent DNSPod (`doh.pub`) — both return the same
record for `idilink-cn.denzacloud.com` and `apr-cn.denzacloud.com`:

```
{"Answer":[{"name":"idilink-cn.denzacloud.com.","TTL":300,"type":1,"data":"1.1.1.1"}]}
```

So the public zone publishes the placeholder to everyone, Chinese resolvers
included. The real address is served by the DNS the carrier hands out *inside*
the APN, and is almost certainly carrier-internal. Consequences:

- A VPN or VPS in China does not help: the name does not resolve there either.
- There is no address to point a relay at, so no DNS override on the owner's
  router can reach the stock telematics.
- Nothing hardcoded on the car fills the gap. The only IP-shaped values in its
  properties are APN health-check targets
  (`persist.radio.byd.ping_apn2_ip1` `120.232.145.185`,
  `ping_apn2_ip2` `59.82.121.200`), not service endpoints.

Reaching the stock cloud therefore requires a SIM on the private APN. Nothing
software-side substitutes for it.

The next section narrows that sentence. It was right about registration and
wrong about there being no public address at all.

## Public MQTT broker is reachable; private registration path is not (2026-09-22, later pass)

> **Superseded 2026-09-23:** registration is reachable on the public profile: 211 to `dilinkreg-cn.denzacloud.com:6001` succeeded over Wi-Fi, and the stock client then got its token (`token_flag` 1) without `10.167.206.17` — see [cloud-protocol.md, Official-cloud registration and login over Wi-Fi verified, 2026-09-23](cloud-protocol.md#official-cloud-registration-and-login-over-wi-fi-verified-2026-09-23) and [cloud-tile.md, Stock-client Wi-Fi adaptation, 2026-09-23](cloud-tile.md#stock-client-wi-fi-adaptation-2026-09-23).

Read-only. No settings, routes, or services were changed. The head unit was
still `127.0.0.1:5555` on Wi-Fi `192.168.88.109`.

`/system/etc/mqttserv/` ships two Denza profiles. The public one is not a
domain that falls back to `1.1.1.1`:

| File | Denza broker |
| --- | --- |
| `broker_pub_8883.json` | `ssl://121.37.227.120:8883`, name `dilink-mqtt-cn.denzacloud.com` |
| `broker_pub_8890.json` | same host, port `8890` |
| `broker_priv_8883.json` | `ssl://10.167.206.92:8883` |
| `broker_priv_1883.json` | `tcp://10.167.206.92:1883` |

`dilink-mqtt-cn.denzacloud.com` is still `1.1.1.1` on Google DNS and on
AliDNS. The IP in the public file is a real server. From this Mac, TCP to
`121.37.227.120:8883` and `:8890` completed in about 0.6 s and negotiated
TLS 1.2. The peer certificate is `CN=dilink-mqtt-cn.denzacloud.com`,
`O=BYD INDUSTRY`. No MQTT connect was sent. The same check on
`113.46.140.234:8883` (the BYD public profile in the same files) also
completed TLS. Ports `5002`, `9102`, and `9105` on both addresses timed out.

That public broker is not where this car is connecting. At the same time,
`/proc/net/tcp` showed root (`uid 0`) in `SYN_SENT` to `10.167.206.17:9105`
from `192.168.88.109`, and an earlier sample in this investigation showed
`10.167.206.17:9102`. `10.167.206.17` is not in the readable mqtt JSON (that
subnet's published broker is `.92`) and `grep` of `/system/etc` does not find
it. The packets leave on the Wi-Fi default route. The home router has no
route to that carrier address, so the handshake never finishes.

`persist.sys.cloud.token_flag` is still `0`, and `CloudServiceApp` still logs
`token is empty or token length less 32` before opening
`emqx-cn.denzacloud.com:8884`. The Dolphin-era names
`dilinkserviceterminalreg-global.iov.byd.auto` and
`dilinkserviceterminaladdr-global.iov.byd.auto` are NXDOMAIN on both
resolvers; this build's host properties remain `apr-cn.denzacloud.com` and
`idilink-cn.denzacloud.com`.

**What this separates.** The MQTT broker the firmware lists as public is on
the internet, and a DNS override still cannot name it because the zone
publishes `1.1.1.1`. The client that has to run before that broker is useful
is aimed at `10.167.206.17:9105`, which does not answer on the public broker
IPs. Redirecting that flow at `121.37.227.120` would hit a closed port.
A VPS, in China or elsewhere, is not inside `10.167.206.0/24`.

The official phone app renders the card from the cloud record
(`getCurrentStateByVin`, `latestVehicleCondition`). Public clients of that
same cloud (pyBYD and the apps it credits) read that record and send remote
commands. They do not upload SOC, range, or the "updated at" timestamp.
`CloudServiceApp` still refuses to open its broker until
`/data/system/cloud/cloudToken.dat` exists, and shell cannot create that
file. A token minted anywhere except the registration server on
`10.167.206.17` is not a path this investigation can complete: the public
broker is what checks it. Phone-to-car casting (ICCOA Carlink / HiCar) mirrors
the phone onto the head unit and does not write this card.

## Stock virtual SIM and the roaming reject (2026-09-22, later pass)

The head unit already has China Mobile's virtual-SIM writer,
`com.xinsheng.videntityserver` (`CmccVidentityserver.apk`), and
`com.byd.vsimservice` is running. `persist.sys.byd.vsim.config` is empty,
and the service treats anything other than `0` as enabled.
`persist.sys.byd.vsim.state` is `0` because no profile is loaded.

The writer downloads a profile over plain HTTP from
`esim.yunbaitech.cn:8085` (`/bydwrite/v1/writeApi/bydToWriteCard`), falling
back to `121.37.10.2:8085`. Both ports answered TCP from this Mac. It does
not get that far on this car. It takes an ICCID from
`persist.radio.byd.vsim.iccid` (empty) or else `ril.csim.iccid`, and
continues only when character 13 of that ICCID is `D`. The factory SIM does
not have that marker, so the download thread exits with "iccid is not
vaild" and never calls the writer. This car is not enrolled in the stock
virtual-SIM path.

The physical modem is not sitting idle. `dumpsys telephony.registry` shows
WWAN voice and packet service alternating through `NOT_REG_SEARCHING`, and
on 2026-09-22 at 09:38 and again at 12:42 the registration info carries
`rejectCause=13` (roaming not allowed in this location area).
`persist.radio.byd.last_mcc=250` and `last_mnc=02` record a Russian network
seen earlier. `persist.sys.byd.apn_type` is `triple_apn` and
`persist.radio.net.lte.apn1.disable` is `0`, so the private APN policy is
on. Android `mobile_data` and `data_roaming` are still `0`; the reject is
at registration, before those switches attach a data bearer.

The owner closed this line the same day: the fitted SIM is the Chinese
one and does not work in Russia. Wi-Fi calling is not a substitute.
`persist.vendor.mtk_wfc_support=1` and the carrier config marks
`carrier_wfc_ims_available_bool` true, but
`carrier_default_wfc_ims_enabled_bool` and
`carrier_default_wfc_ims_roaming_enabled_bool` are false,
`carrier_wfc_supports_wifi_only_bool` is false, and both ePDG address
strings are empty. That tunnel would still authenticate this same SIM
to China Mobile, and it carries IMS, not the `CMIOTBYDNSA.GD` telematics
APN.

## Not established yet (2026-09-22, superseded)

> **Superseded 2026-09-23:** stale list. The executable is readable and the archive decoded ([firmware-reading.md, Matching archive decoded and cloud client recovered, 2026-09-23](firmware-reading.md#matching-archive-decoded-and-cloud-client-recovered-2026-09-23)); the status card is fed by the head unit's own message 512 ([cloud-protocol.md, Real SOC reached the official phone app over Wi-Fi, 2026-09-23](cloud-protocol.md#real-soc-reached-the-official-phone-app-over-wi-fi-2026-09-23)); registration works over Wi-Fi. The items still open (incoming commands, local SIM, the owner gate) are in the open questions of [README](README.md#current-state).

- **Whether the established sockets carry this car's telematics.** A root UID
  and port 30023 do not establish the process, protocol or purpose.
- **Whether the cloud reaches the car when the app asks.** The planned test —
  watch the car's conntrack while the app refreshes — did not run: the phone
  locked before the app reached the foreground.
- **Which module owns the report.** Everything measured is the head unit's own
  MTK modem (`MOLY.NR16.R1.MP3.MP.V2.P5`). A separate T-Box ECU, if it holds
  its own radio, is not visible from the IVI's shell.
- **Why native registration/token delivery fails.** The current executable is
  unreadable to shell and the matching archive's inner image is not yet decoded.
- The remote-location key is now found in the BYD settings provider;
  `remote_location=0` and `remote_screenage=0` were read. Current Java code
  packs these as privacy flags, without an observed general registration
  switch in that path. Broader native effects remain unknown.
- Whether a local SIM in slot 0 would be usable, given that the APN and the
  vehicle's data plan are provisioned for a mainland carrier.
