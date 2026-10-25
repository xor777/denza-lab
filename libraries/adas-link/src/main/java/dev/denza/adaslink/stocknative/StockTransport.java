package dev.denza.adaslink.stocknative;

/** Original SOME/IP wrapper boundary. Return codes are independent recorded environment inputs. */
public interface StockTransport {
    int fire(long topic,byte[] payload);
    int startService(long topic);
    int stopService(long topic);
    int request(long topic,byte[] payload,boolean withResponse);
}
