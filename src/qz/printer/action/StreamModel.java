package qz.printer.action;

import org.apache.commons.codec.binary.Base64InputStream;
import org.apache.commons.io.Charsets;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qz.printer.PrintOptions;
import qz.printer.PrintOutput;
import qz.utils.ByteUtilities;
import qz.utils.PrintingUtilities;

import javax.print.PrintException;
import java.awt.print.PrinterException;
import java.io.*;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public class StreamModel {

    private static final Logger log = LoggerFactory.getLogger(StreamModel.class);

    private PrintOptions options;
    private PrintOutput output;

    private Path tempFile;
    private OutputStream outputFileStream;

    private boolean printReady = false;

    private PrintingUtilities.Format lastFormat = PrintingUtilities.Format.PLAIN;


    public StreamModel(PrintOutput output, PrintOptions options) throws IOException {
        this.output = output;
        this.options = options;

        tempFile = Files.createTempFile("printjob", ".tmp");
        tempFile.toFile().deleteOnExit();
        outputFileStream = FileUtils.openOutputStream(tempFile.toFile(), true);
    }

    public void parseData(JSONArray printData) throws JSONException, UnsupportedOperationException {
        InputStream input = null;
        try {
            for(int i = 0; i < printData.length(); i++) {
                JSONObject data = printData.optJSONObject(i);
                if (data == null) { continue; }

                printReady = printData.optJSONObject(i).optBoolean("lastChunk", false);
                lastFormat = PrintingUtilities.Format.valueOf(data.optString("format", lastFormat.toString()).toUpperCase(Locale.ENGLISH));

                switch(lastFormat) {
                    case BASE64:
                        input = new Base64InputStream(new ByteArrayInputStream(data.getString("data").getBytes(Charsets.UTF_8)));
                        break;
                    case FILE: case STREAM:
                        input = new DataInputStream(new URL(data.getString("data")).openStream());
                        break;
                    case IMAGE:
                        //unimplemented
                        throw new UnsupportedOperationException("Image chunks are not supported at this time");
                    case HEX:
                        input = new ByteArrayInputStream(ByteUtilities.hexStringToByteArray(data.getString("data")));
                        break;
                    case XML:
                        //unimplemented
                        throw new UnsupportedOperationException("XML chunks are not supported at this time");
                    case PLAIN:
                    default:
                        input = IOUtils.toInputStream(data.getString("data"));
                        break;
                }

                if (input != null) {
                    IOUtils.copy(input, outputFileStream);
                }

                //old data will hang around and build up, defeating the purpose of streaming. To fix this, a GC event is suggested to the JRE.
                //this prevents memory spikes at the cost of cpu cycles. Note, system.gc is not a guaranteed operation
                System.gc();
            }
        }
        catch(IOException e) {
            log.error("Parse data failed ", e);
            throw new UnsupportedOperationException(e);
        }
        finally {
            if (input != null) {
                try { input.close(); } catch(Exception ignore) {}
            }
        }
    }

    public boolean isPrintReady() {
        return printReady;
    }

    public void sendToPrint(PrintProcessor processor) throws JSONException, IOException, PrinterException, PrintException {
        //prepare a new data block to be parsed by the print processor
        JSONObject preparedData = new JSONObject();
        preparedData.put("data", tempFile.toAbsolutePath());
        preparedData.put("format", "stream");

        JSONArray preparedArray = new JSONArray();
        preparedArray.put(preparedData);

        processor.parseData(preparedArray, options);
        processor.print(output, options);
    }

    public void close() {
        try {
            outputFileStream.flush();
            outputFileStream.close();
            Files.deleteIfExists(tempFile);
        }
        catch(IOException e) {
            log.error("Print processor failed cleanup ", e);
        }
    }

}
