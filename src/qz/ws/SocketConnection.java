package qz.ws;

import jssc.SerialPortException;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.eclipse.jetty.websocket.api.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qz.auth.Certificate;
import qz.communication.*;
import qz.printer.PrintOptions;
import qz.printer.PrintOutput;
import qz.printer.action.*;
import qz.utils.PrintingUtilities;

import java.util.Dictionary;
import java.util.HashMap;
import java.util.Hashtable;

public class SocketConnection {

    private static final Logger log = LoggerFactory.getLogger(SocketConnection.class);


    private Certificate certificate;

    private DeviceListener deviceListener;

    private Dictionary<String,PrintDirect> printStreams;

    // serial port -> open SerialIO
    private final HashMap<String,SerialIO> openSerialPorts = new HashMap<>();

    // DeviceOptions -> open DeviceIO
    private final HashMap<DeviceOptions,DeviceIO> openDevices = new HashMap<>();


    public SocketConnection(Certificate cert) {
        certificate = cert;
    }

    public Certificate getCertificate() {
        return certificate;
    }

    public void setCertificate(Certificate newCert) {
        certificate = newCert;
    }


    public void addSerialPort(String port, SerialIO io) {
        openSerialPorts.put(port, io);
    }

    public SerialIO getSerialPort(String port) {
        return openSerialPorts.get(port);
    }

    public void removeSerialPort(String port) {
        openSerialPorts.remove(port);
    }


    public boolean isListening() {
        return deviceListener != null;
    }

    public void startListening(DeviceListener listener) {
        deviceListener = listener;
    }

    public void stopListening() {
        if (deviceListener != null) {
            deviceListener.close();
        }
        deviceListener = null;
    }


    public void addDevice(DeviceOptions dOpts, DeviceIO io) {
        openDevices.put(dOpts, io);
    }

    public DeviceIO getDevice(DeviceOptions dOpts) {
        return openDevices.get(dOpts);
    }

    public void removeDevice(DeviceOptions dOpts) {
        openDevices.remove(dOpts);
    }

    public synchronized void openDevice(DeviceIO device, DeviceOptions dOpts) throws DeviceException {
        device.open();
        addDevice(dOpts, device);
    }

    public void initPrintStream(Certificate cert, Session session, String UID, JSONObject params) throws JSONException {
        String fingerprint = "!";
        String streamUID = params.getJSONArray("data").getJSONObject(0).getString("streamUID");
        if (cert.isTrusted()) fingerprint = cert.getFingerprint();
        if (printStreams == null) printStreams = new Hashtable<>();

        PrintDirect printProcessor = (PrintDirect)PrintingUtilities.getPrintProcessor(params.getJSONArray("data"));
        PrintOutput output = new PrintOutput(params.optJSONObject("printer"));
        PrintOptions options = new PrintOptions(params.optJSONObject("options"), output);
        printProcessor.init(output, options);
        printStreams.put(fingerprint + streamUID, printProcessor);

        processPrintStream(cert, session, UID, params);
    }

    public void processPrintStream(Certificate cert, Session session, String UID, JSONObject params) throws JSONException {
        String fingerprint = "!";
        String streamUID = params.getJSONArray("data").getJSONObject(0).getString("streamUID");
        if (cert.isTrusted()) fingerprint = cert.getFingerprint();

        PrintDirect printProcessor = printStreams.get(fingerprint + streamUID);
        try {
            printProcessor.parseData(params.getJSONArray("data"), null);
            if (printProcessor.isReady()) printProcessor.print(null, null);

            PrintSocketClient.sendResult(session, UID, null);
        }
        catch(Exception e) {
            log.error("Failed to print", e);
            printStreams.remove(fingerprint + streamUID);
            PrintingUtilities.releasePrintProcessor(printProcessor);
            PrintSocketClient.sendError(session, UID, e);
        }
        finally {
            if (printProcessor.isEOL()) {
                printStreams.remove(fingerprint + streamUID);
                PrintingUtilities.releasePrintProcessor(printProcessor);
                log.info("Printing complete");
            }
        }
    }

    /**
     * Explicitly closes all open serial and usb connections setup through this object
     */
    public synchronized void disconnect() throws SerialPortException, DeviceException {
        log.info("Closing all communication channels for {}", certificate.getCommonName());

        for(String p : openSerialPorts.keySet()) {
            openSerialPorts.get(p).close();
        }

        for(DeviceIO dio : openDevices.values()) {
            dio.setStreaming(false);
            dio.close();
        }

        stopListening();
    }

}
