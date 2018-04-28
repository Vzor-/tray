package qz.printer.action;

import org.apache.commons.codec.binary.Base64InputStream;
import org.apache.commons.ssl.Base64;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.eclipse.jetty.websocket.api.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qz.common.Constants;
import qz.printer.PrintOptions;
import qz.printer.PrintOutput;
import qz.utils.PrintingUtilities;

import javax.print.DocFlavor;
import javax.print.DocPrintJob;
import javax.print.PrintException;
import javax.print.SimpleDoc;
import javax.print.attribute.HashPrintRequestAttributeSet;
import javax.print.attribute.PrintRequestAttributeSet;
import javax.print.attribute.standard.JobName;
import java.io.*;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Locale;

public class PrintDirect extends PrintRaw {

    private static final Logger log = LoggerFactory.getLogger(PrintDirect.class);

    private String streamUID;
    private String fingerprint;
    private PrintOptions options;
    private Path tempFile;

    private PrintingUtilities.Format lastFormat;

    @Override
    public PrintingUtilities.Type getType() {
        return PrintingUtilities.Type.DIRECT;
    }

    @Override
    public void parseData(JSONArray printData, PrintOptions options) throws JSONException, UnsupportedOperationException {
        for(int i = 0; i < printData.length(); i++) {
            JSONObject data = printData.optJSONObject(i);
            if (data == null) { continue; }
            try {
                if (tempFile == null) {
                    tempFile = Files.createTempFile("printjob", ".tmp");
                }
                PrintingUtilities.Format format = PrintingUtilities.Format.valueOf(data.optString("format", "PLAIN").toUpperCase(Locale.ENGLISH));
                //Todo Remove this debugging log
                log.warn(tempFile.toAbsolutePath().toUri().toString());
                switch(format) {
                    case BASE64:
                        Files.write(tempFile, Base64.decodeBase64(data.getString("data")), StandardOpenOption.APPEND);
                        break;
                    case FILE:
                        //stream = new DataInputStream(new URL(prints.get(i)).openStream());
                        break;
                    case PLAIN:
                    default:
                        Files.write(tempFile, data.getString("data").getBytes(), StandardOpenOption.APPEND);
                        break;
                }
            }
            catch(IOException e) {
                //Todo Remove this debugging log
                log.warn(e.getMessage());
            }
        }
    }

    @Override
    public void print(PrintOutput output, PrintOptions options) throws PrintException {
    }

    @Override
    public void cleanup() {
    }

}
