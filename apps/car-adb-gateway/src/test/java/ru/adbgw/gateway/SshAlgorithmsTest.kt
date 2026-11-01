package ru.adbgw.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The car's algorithm lists against what the other ends offer. SSH picks, per category, the first
 * client algorithm the server also has: towards the relay the car is the client, towards the
 * computer's OpenSSH the car is the server.
 */
class SshAlgorithmsTest {
    // getName(), the SSH name: on these enums Kotlin's `name` is the constant's.
    private val kex = SshAlgorithms.keyExchanges.map { it.getName() }
    private val ciphers = SshAlgorithms.ciphers.map { it.getName() }
    private val macs = SshAlgorithms.macs.map { it.getName() }

    @Test
    fun `the lists are the light ones, in order, and all run on this platform`() {
        assertEquals(listOf("ecdh-sha2-nistp256", "curve25519-sha256", "diffie-hellman-group14-sha256"), kex)
        assertEquals(listOf("aes128-gcm@openssh.com", "aes128-ctr"), ciphers)
        assertEquals(listOf("hmac-sha2-256-etm@openssh.com", "hmac-sha2-256"), macs)
        assertTrue("no post-quantum hybrid", kex.none { it.startsWith("mlkem") || it.startsWith("sntrup") })
        assertEquals(kex, SshAlgorithms.clientKeyExchanges().map { it.name })
        assertEquals(kex, SshAlgorithms.serverKeyExchanges().map { it.name })
        assertEquals(ciphers, SshAlgorithms.cipherFactories().map { it.name })
        assertEquals(macs, SshAlgorithms.macFactories().map { it.name })
    }

    @Test
    fun `the car gets P-256 and AES-GCM from the relay's OpenSSH 10_2`() {
        assertEquals("ecdh-sha2-nistp256", negotiate(kex, RELAY_KEX))
        assertEquals("aes128-gcm@openssh.com", negotiate(ciphers, OPENSSH_10_CIPHERS))
        assertEquals("hmac-sha2-256-etm@openssh.com", negotiate(macs, OPENSSH_MACS))
        // Every one of the car's choices is a fallback the relay also has.
        assertTrue(RELAY_KEX.containsAll(kex))
        assertTrue(OPENSSH_10_CIPHERS.containsAll(ciphers))
        assertTrue(OPENSSH_MACS.containsAll(macs))
    }

    @Test
    fun `a default OpenSSH 9 or 10 client negotiates with the inner server`() {
        for ((name, offer) in mapOf("OpenSSH 9.0" to OPENSSH_9_CLIENT, "OpenSSH 10.x" to OPENSSH_10_CLIENT)) {
            val chosen = listOf(
                negotiate(offer.kex, kex),
                negotiate(offer.ciphers, ciphers),
                negotiate(offer.macs, macs),
            )
            println("[$name -> inner server] $chosen")
            assertTrue("$name: $chosen", chosen.all { it != null })
            assertEquals("curve25519-sha256", chosen[0])
        }
    }

    @Test
    fun `the local OpenSSH client's defaults negotiate with the inner server`() {
        val defaults = localOpenSshDefaults()
        assumeTrue("no ssh on PATH", defaults != null)
        val offer = defaults!!

        val chosen = listOf(negotiate(offer.kex, kex), negotiate(offer.ciphers, ciphers), negotiate(offer.macs, macs))

        println("[local ${offer.version} -> inner server] $chosen")
        chosen.forEach { assertNotNull("local client: $offer", it) }
    }

    private fun negotiate(client: List<String>, server: List<String>): String? = client.firstOrNull { it in server }

    private data class Offer(val kex: List<String>, val ciphers: List<String>, val macs: List<String>, val version: String = "")

    /** `ssh -F /dev/null -G`: the client's built-in defaults, with no user or system config. */
    private fun localOpenSshDefaults(): Offer? {
        val ssh = System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .map { File(it, "ssh") }
            .firstOrNull { it.canExecute() } ?: return null
        val process = ProcessBuilder(ssh.path, "-F", "/dev/null", "-G", "cag.invalid")
            .redirectErrorStream(true)
            .start()
        val lines = process.inputStream.bufferedReader().readLines()
        if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) return null
        fun option(name: String) = lines.firstOrNull { it.startsWith("$name ") }
            ?.substringAfter(' ')?.split(',').orEmpty()
        val version = ProcessBuilder(ssh.path, "-V").redirectErrorStream(true).start()
            .inputStream.bufferedReader().readText().trim()
        return Offer(option("kexalgorithms"), option("ciphers"), option("macs"), version)
            .takeIf { it.kex.isNotEmpty() && it.ciphers.isNotEmpty() && it.macs.isNotEmpty() }
    }

    companion object {
        /** `sshd -T` on the relay (OpenSSH 10.2, Ubuntu 26.04, default KexAlgorithms). */
        private val RELAY_KEX = listOf(
            "mlkem768x25519-sha256",
            "sntrup761x25519-sha512",
            "curve25519-sha256",
            "ecdh-sha2-nistp256",
            "ecdh-sha2-nistp384",
            "ecdh-sha2-nistp521",
            "diffie-hellman-group-exchange-sha256",
            "diffie-hellman-group16-sha512",
            "diffie-hellman-group18-sha512",
            "diffie-hellman-group14-sha256",
        )
        private val OPENSSH_10_CIPHERS = listOf(
            "chacha20-poly1305@openssh.com",
            "aes128-gcm@openssh.com",
            "aes256-gcm@openssh.com",
            "aes128-ctr",
            "aes192-ctr",
            "aes256-ctr",
        )
        private val OPENSSH_MACS = listOf(
            "umac-64-etm@openssh.com",
            "umac-128-etm@openssh.com",
            "hmac-sha2-256-etm@openssh.com",
            "hmac-sha2-512-etm@openssh.com",
            "hmac-sha1-etm@openssh.com",
            "umac-64@openssh.com",
            "umac-128@openssh.com",
            "hmac-sha2-256",
            "hmac-sha2-512",
            "hmac-sha1",
        )
        private val OPENSSH_9_CLIENT = Offer(
            kex = listOf(
                "sntrup761x25519-sha512@openssh.com",
                "curve25519-sha256",
                "curve25519-sha256@libssh.org",
                "ecdh-sha2-nistp256",
                "ecdh-sha2-nistp384",
                "ecdh-sha2-nistp521",
                "diffie-hellman-group-exchange-sha256",
                "diffie-hellman-group16-sha512",
                "diffie-hellman-group18-sha512",
                "diffie-hellman-group14-sha256",
            ),
            ciphers = listOf(
                "chacha20-poly1305@openssh.com",
                "aes128-ctr",
                "aes192-ctr",
                "aes256-ctr",
                "aes128-gcm@openssh.com",
                "aes256-gcm@openssh.com",
            ),
            macs = OPENSSH_MACS,
        )
        private val OPENSSH_10_CLIENT = Offer(
            kex = listOf("mlkem768x25519-sha256", "sntrup761x25519-sha512", "sntrup761x25519-sha512@openssh.com") +
                OPENSSH_9_CLIENT.kex.drop(1),
            ciphers = OPENSSH_10_CIPHERS,
            macs = OPENSSH_MACS,
        )
    }
}
