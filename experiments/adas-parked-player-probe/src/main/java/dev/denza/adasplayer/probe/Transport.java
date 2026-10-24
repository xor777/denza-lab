package dev.denza.adasplayer.probe;

/**
 * The player's own SOME/IP session: starting/stopping the four server services (7, 43, 45, 0x8202)
 * and firing events on them, plus the one ADAS client request the stock sends before a route (the
 * ODD region list, service 13 / method 0x0002). Never fires 13/0x0001 (the DNP/ICC switch): there
 * is no method for it here. The Android implementation uses {@code HudSomeIp}; the harness fakes it.
 */
interface Transport {
    int startServer(long serviceTopic);

    int stopServer(long serviceTopic);

    int fire(long topic, byte[] payload);

    /** The stock's ODD region-code request on service 13 / method 0x0002; the call result. */
    int oddRequest();
}
