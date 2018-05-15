package qz.ws;

import jssc.SerialPortException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qz.auth.Certificate;
import qz.communication.*;
import qz.printer.action.StreamModel;

import java.util.HashMap;

public class SocketConnection {

    private static final Logger log = LoggerFactory.getLogger(SocketConnection.class);


    private Certificate certificate;

    private DeviceListener deviceListener;

    // fingerprint -> pending PrintStream
    private HashMap<String,StreamModel> printStreams = new HashMap<>();

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

    public synchronized void addStream(String key, StreamModel processor) {
        printStreams.put(key, processor);
    }

    public synchronized StreamModel getStream(String key) {
        return printStreams.get(key);
    }

    public synchronized StreamModel removeStream(String key) {
        return printStreams.remove(key);
    }

    /**
     * Explicitly closes all open serial and usb connections setup through this object
     */
    public synchronized void disconnect() throws SerialPortException, DeviceException {
        log.info("Closing all communication channels for {}", certificate.getCommonName());

        for(StreamModel stream : printStreams.values()) {
            stream.close();
        }

        for(SerialIO sio : openSerialPorts.values()) {
            sio.close();
        }

        for(DeviceIO dio : openDevices.values()) {
            dio.setStreaming(false);
            dio.close();
        }

        stopListening();
    }

}
