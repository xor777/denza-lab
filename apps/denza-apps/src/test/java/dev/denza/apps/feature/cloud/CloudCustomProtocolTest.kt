package dev.denza.apps.feature.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class CloudCustomProtocolTest {
    private fun reply(id: Int = 1, op: String = "PROBE", stage: String = "stopped",
                      code: String = "owner_absent_confirmed", live: Boolean = false) = """
        {"id":$id,"op":"$op","ok":true,"protocol":3,"profile":"awake-alpha-v1",
         "lease_until_uptime_ms":${if (live) 30000 else 0},"lease_active":$live,"pid":97,"session_live":$live,
         "stage":"$stage","code":"$code","updated_elapsed_ms":100,"connected_elapsed_ms":0,
         "last_rx_elapsed_ms":0,"last_tx_elapsed_ms":0,"last_report_elapsed_ms":0,
         "next_retry_elapsed_ms":0,"attempts":0,"reports_sent":0,"status_replies":0,
         "commands_forwarded":0,"commands_completed":0,"reconnects":0,"callback_age_ms":-1,
         "native_events":[],"owner_id":"${if (live || code == "owner_present") "a".repeat(32) else ""}",
         "runtime_id":"${"b".repeat(12)}-${"c".repeat(12)}", "config_generation":${if (live || code == "owner_present") 1 else 0},
         "capabilities":${if (live) """["reg","data","control_awake","wake_ack_awake","timers_awake","post_login_awake","heartbeat","power_guard"]""" else "[]"},
         "retryable":false,"registration_uncertain":false}
    """.trimIndent()

    @Test fun probeOnlyAcceptsExactGlobalOwnerProof() {
        assertEquals("owner_absent_confirmed", CloudCustomProtocol.answer(reply(), 1, "PROBE").code)
        assertEquals("owner_present", CloudCustomProtocol.answer(reply(stage = "failed", code = "owner_present"), 1, "PROBE").code)
        assertThrows(IllegalStateException::class.java) {
            CloudCustomProtocol.answer(reply(code = "maybe_absent"), 1, "PROBE")
        }
        assertThrows(IllegalStateException::class.java) {
            CloudCustomProtocol.answer(reply(id = 2), 1, "PROBE")
        }
    }

    @Test fun onlyConfirmedWorkerLiveStatusCanBeConnected() {
        val connected = CloudCustomProtocol.answer(reply(op = "STATUS", stage = "connected", code = "none", live = true), 1, "STATUS")
        assertTrue(connected.sessionLive)
        assertThrows(IllegalStateException::class.java) {
            CloudCustomProtocol.answer(reply(op = "STATUS", stage = "starting", code = "none", live = true), 1, "STATUS")
        }
        assertThrows(IllegalStateException::class.java) {
            CloudCustomProtocol.answer(reply(op = "STATUS").replace("\"native_events\":[]", "\"native_events\":null"), 1, "STATUS")
        }
    }

    @Test fun startIdentityGoesOnlyIntoStartRequest() {
        val identity = CloudIdentity("89860712345678901234", "460011234567890")
        assertTrue(CloudCustomProtocol.request(1, "START", identity,
            serviceInstance = "f".repeat(32), renewSeq = 1).contains(identity.iccid))
        assertFalse(CloudCustomProtocol.request(2, "STATUS").contains(identity.iccid))
        assertFalse(CloudCustomProtocol.request(3, "PROBE").contains(identity.imsi))
        assertFalse(CloudCustomProtocol.request(4, "STOP", ownerId = "").contains(identity.imsi))
        assertFalse(CloudCustomProtocol.request(5, "ATTACH", ownerId = "a".repeat(32),
            serviceInstance = "f".repeat(32)).contains(identity.imsi))
        assertThrows(IllegalArgumentException::class.java) { CloudCustomProtocol.request(0, "STATUS") }
    }

    @Test fun awakeAlphaRequestsBindStartAttachRenewAndStopPrecisely() {
        val instance = "f".repeat(32)
        val owner = "a".repeat(32)
        val identity = CloudIdentity("89860712345678901234", "460011234567890")
        val start = JSONObject(CloudCustomProtocol.request(1, "START", identity,
            serviceInstance = instance, renewSeq = 1))
        assertEquals(3, start.getInt("protocol"))
        assertEquals("awake-alpha-v1", start.getString("profile"))
        assertEquals(instance, start.getString("service_instance"))
        assertEquals(1L, start.getLong("renew_seq"))
        val attach = JSONObject(CloudCustomProtocol.request(2, "ATTACH", ownerId = owner,
            serviceInstance = instance))
        assertEquals(owner, attach.getString("owner_id"))
        assertFalse(attach.has("renew_seq"))
        val renew = JSONObject(CloudCustomProtocol.request(3, "RENEW", ownerId = owner,
            serviceInstance = instance, renewSeq = 2))
        assertEquals(2L, renew.getLong("renew_seq"))
        assertFalse(renew.has("iccid"))
        val stop = JSONObject(CloudCustomProtocol.request(4, "STOP", ownerId = owner,
            serviceInstance = instance))
        assertEquals(owner, stop.getString("owner_id"))
        assertEquals(instance, stop.getString("service_instance"))
        val debtStop = JSONObject(CloudCustomProtocol.request(8, "STOP", ownerId = ""))
        assertEquals("", debtStop.getString("service_instance"))
        for (op in listOf("PROBE", "STATUS")) {
            val request = JSONObject(CloudCustomProtocol.request(5, op))
            assertEquals(3, request.length())
        }
        assertThrows(IllegalArgumentException::class.java) {
            CloudCustomProtocol.request(6, "RENEW", ownerId = owner, serviceInstance = instance, renewSeq = 0)
        }
    }

    @Test fun legacyTwoIsAcceptedOnlyForCleanup() {
        val legacyProbe = JSONObject(reply()).put("protocol", 2)
        legacyProbe.remove("profile"); legacyProbe.remove("lease_until_uptime_ms"); legacyProbe.remove("lease_active")
        assertEquals(2, CloudCustomProtocol.answer(legacyProbe.toString(), 1, "PROBE", true).protocol)
        assertThrows(IllegalStateException::class.java) {
            CloudCustomProtocol.answer(legacyProbe.toString(), 1, "PROBE")
        }
        val legacyStart = JSONObject(legacyProbe.toString()).put("op", "START")
        assertThrows(IllegalStateException::class.java) {
            CloudCustomProtocol.answer(legacyStart.toString(), 1, "START", true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CloudCustomProtocol.request(7, "START", legacyCleanup = true)
        }
    }

    @Test fun typedRejectionStillRequiresCompleteVersionThreeEnvelope() {
        val rejection = JSONObject(reply(op = "START")).put("ok", false)
            .put("stage", "failed").put("code", "unsupported_firmware")
        val error = assertThrows(CloudCustomRejected::class.java) {
            CloudCustomProtocol.answer(rejection.toString(), 1, "START")
        }
        assertEquals("unsupported_firmware", error.code)
        assertFalse(error.retryable)
        rejection.remove("registration_uncertain")
        assertThrows(IllegalStateException::class.java) {
            CloudCustomProtocol.answer(rejection.toString(), 1, "START")
        }
    }

    @Test fun rejectsOldProtocolDuplicateCapabilitiesAndImpossibleOwner() {
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.put("protocol", 1) },
            { it.put("capabilities", org.json.JSONArray(listOf("reg", "reg"))) },
            { it.put("capabilities", org.json.JSONArray(listOf("any_command"))) },
            { it.put("owner_id", "a".repeat(32)) }, // generation zero cannot describe an owner
            { it.put("runtime_id", "unversioned") },
            { it.put("code", true) },
        )
        for (mutate in mutations) {
            val changed = JSONObject(reply()).also(mutate)
            assertThrows(IllegalStateException::class.java) {
                CloudCustomProtocol.answer(changed.toString(), 1, "PROBE")
            }
        }
    }

    @Test fun fractionalNegativeAndReorderedNativeFactsAreRejected() {
        assertThrows(IllegalStateException::class.java) {
            CloudCustomProtocol.answer(reply().replace("\"attempts\":0", "\"attempts\":1.5"), 1, "PROBE")
        }
        assertThrows(IllegalStateException::class.java) {
            CloudCustomProtocol.answer(reply().replace("\"updated_elapsed_ms\":100", "\"updated_elapsed_ms\":-1"), 1, "PROBE")
        }
        val first = "{\"seq\":2,\"t_ms\":100,\"event\":\"retry\",\"value\":123456789012345}"
        val second = "{\"seq\":1,\"t_ms\":101,\"event\":\"retry\",\"value\":0}"
        assertThrows(IllegalStateException::class.java) {
            CloudCustomProtocol.answer(reply().replace("\"native_events\":[]", "\"native_events\":[$first,$second]"), 1, "PROBE")
        }
        val accepted = CloudCustomProtocol.answer(reply().replace("\"native_events\":[]", "\"native_events\":[$first]"), 1, "PROBE")
        assertFalse(accepted.events.toString().contains("123456789012345"))
    }
}
