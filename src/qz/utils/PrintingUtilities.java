package qz.utils;

import org.apache.commons.pool2.impl.GenericKeyedObjectPool;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.eclipse.jetty.websocket.api.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qz.auth.Certificate;
import qz.common.Constants;
import qz.printer.PrintOptions;
import qz.printer.PrintOutput;
import qz.printer.action.StreamModel;
import qz.printer.action.PrintProcessor;
import qz.printer.action.ProcessorFactory;
import qz.ws.PrintSocketClient;
import qz.ws.SocketConnection;

import javax.print.PrintService;
import javax.print.attribute.ResolutionSyntax;
import javax.print.attribute.standard.PrinterResolution;
import java.awt.print.PrinterAbortException;
import java.io.IOException;
import java.util.*;

public class PrintingUtilities {

    private static final Logger log = LoggerFactory.getLogger(PrintingUtilities.class);

    private static HashMap<String,String> CUPS_DESC; //name -> description
    private static HashMap<String,PrinterResolution> CUPS_DPI; //description -> default dpi

    private static GenericKeyedObjectPool<Type,PrintProcessor> processorPool;


    private PrintingUtilities() {}

    public enum Type {
        HTML, IMAGE, PDF, RAW
    }

    public enum Format {
        BASE64, FILE, IMAGE, PLAIN, HEX, XML, STREAM
    }


    public synchronized static PrintProcessor getPrintProcessor(JSONArray printData) throws JSONException {
        JSONObject data = printData.optJSONObject(0);

        Type type;
        if (data == null) {
            type = Type.RAW;
        } else {
            type = Type.valueOf(data.optString("type", "RAW").toUpperCase(Locale.ENGLISH));
        }

        try {
            if (processorPool == null) {
                processorPool = new GenericKeyedObjectPool<>(new ProcessorFactory());

                long memory = Runtime.getRuntime().maxMemory() / 1000000;
                if (memory < Constants.MEMORY_PER_PRINT) {
                    log.warn("Memory available is less than minimum required ({}/{} MB)", memory, Constants.MEMORY_PER_PRINT);
                }
                if (memory < Long.MAX_VALUE) {
                    int maxInst = Math.max(1, (int)(memory / Constants.MEMORY_PER_PRINT));
                    log.debug("Allowing {} simultaneous processors based on memory available ({} MB)", maxInst, memory);
                    processorPool.setMaxTotal(maxInst);
                    processorPool.setMaxTotalPerKey(maxInst);
                }
            }

            log.trace("Waiting for processor, {}/{} already in use", processorPool.getNumActive(), processorPool.getMaxTotal());
            return processorPool.borrowObject(type);
        }
        catch(Exception e) {
            throw new IllegalArgumentException(String.format("Unable to find processor for %s type", type.name()));
        }
    }

    public static void releasePrintProcessor(PrintProcessor processor) {
        try {
            log.trace("Returning processor back to pool");
            processorPool.returnObject(processor.getType(), processor);
        }
        catch(Exception ignore) {}
    }

    /**
     * Gets the printerId for use with CUPS commands
     *
     * @return Id of the printer for use with CUPS commands
     */
    public static String getPrinterId(String printerName) {
        if (CUPS_DESC == null || !CUPS_DESC.containsValue(printerName)) {
            CUPS_DESC = ShellUtilities.getCupsPrinters();
        }

        if (SystemUtilities.isMac()) {
            for(String name : CUPS_DESC.keySet()) {
                if (CUPS_DESC.get(name).equals(printerName)) {
                    return name;
                }
            }
            log.warn("Could not locate printerId matching {}", printerName);
        }
        return printerName;
    }

    public static PrinterResolution getNativeDensity(PrintService service) {
        if (service == null) { return null; }

        PrinterResolution pRes = (PrinterResolution)service.getDefaultAttributeValue(PrinterResolution.class);

        if (pRes == null && !SystemUtilities.isWindows()) {
            String printerId = getPrinterId(service.getName());

            if (CUPS_DPI == null || !CUPS_DPI.containsKey(printerId)) {
                CUPS_DPI = ShellUtilities.getCupsDensities(CUPS_DESC);
            }

            return CUPS_DPI.get(printerId);
        }

        log.debug("Found Resolution: {}", pRes);
        return pRes;
    }

    public static List<Integer> getSupportedDensities(PrintService service) {
        List<Integer> densities = new ArrayList<>();

        PrinterResolution[] resSupport = (PrinterResolution[])service.getSupportedAttributeValues(PrinterResolution.class, service.getSupportedDocFlavors()[0], null);
        if (resSupport != null) {
            for(PrinterResolution res : resSupport) {
                densities.add(res.getFeedResolution(ResolutionSyntax.DPI));
            }
        }

        return densities;
    }


    /**
     * Determine print variables and send data to printer
     *
     * @param session WebSocket session
     * @param UID     ID of call from web API
     * @param params  Params of call from web API
     */
    public static void processPrintRequest(Session session, String UID, JSONObject params) throws JSONException {
        PrintProcessor processor = getPrintProcessor(params.getJSONArray("data"));
        log.debug("Using {} to print", processor.getClass().getName());

        try {
            PrintOutput output = new PrintOutput(params.optJSONObject("printer"));
            PrintOptions options = new PrintOptions(params.optJSONObject("options"), output);

            processor.parseData(params.getJSONArray("data"), options);
            processor.print(output, options);
            log.info("Printing complete");

            PrintSocketClient.sendResult(session, UID, null);
        }
        catch(PrinterAbortException e) {
            log.warn("Printing cancelled");
            PrintSocketClient.sendError(session, UID, "Printing cancelled");
        }
        catch(Exception e) {
            log.error("Failed to print", e);
            PrintSocketClient.sendError(session, UID, e);
        }
        finally {
            releasePrintProcessor(processor);
        }
    }

    /**
     * Continues a stream that is keyed to the connection + cert Fingerprint + streamUID.
     *  @param session    WebSocket session
     * @param UID        ID of call from web API
     * @param connection SocketConnection that the stream reference is stored within
     * @param params     Params of call from web API
     */
    public static void processPrintStream(Session session, String UID, SocketConnection connection, JSONObject params) throws JSONException {
        String streamUID = params.getJSONArray("data").getJSONObject(0).getString("streamUID");

        StreamModel stream = connection.getStream(streamUID);
        if (stream == null) {
            PrintOutput output = new PrintOutput(params.optJSONObject("printer"));
            PrintOptions options = new PrintOptions(params.optJSONObject("options"), output);

            try {
                stream = new StreamModel(output, options);
                connection.addStream(streamUID, stream);
            }
            catch(IOException e) {
                PrintSocketClient.sendError(session, UID, e);
                return;
            }
        }

        //grab a processor every time, even if we don't end up printing
        PrintProcessor processor = getPrintProcessor(params.getJSONArray("data"));

        try {
            stream.parseData(params.getJSONArray("data"));
            if (stream.isPrintReady()) {
                stream.sendToPrint(processor);
            }

            PrintSocketClient.sendResult(session, UID, null);
        }
        catch(Exception e) {
            log.error("Failed to print", e);
            connection.removeStream(streamUID);
            PrintSocketClient.sendError(session, UID, e);
        }
        finally {
            releasePrintProcessor(processor);

            if (stream.isPrintReady()) {
                connection.removeStream(streamUID);
                log.info("Printing complete");
            }
        }
    }

}
