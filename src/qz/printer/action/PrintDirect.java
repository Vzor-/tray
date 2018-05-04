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
import qz.common.Constants;
import qz.printer.PrintOptions;
import qz.printer.PrintOutput;
import qz.utils.ByteUtilities;
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
import java.util.Locale;

public class PrintDirect extends PrintRaw {

    private static final Logger log = LoggerFactory.getLogger(PrintDirect.class);

    private PrintOptions options;
    private PrintOutput output;
    private Path tempFile;
    private OutputStream outputFileStream;
    private Boolean printReady = false;
    private Boolean streamEOL = false;
    private PrintingUtilities.Format lastFormat = PrintingUtilities.Format.PLAIN;

    @Override
    public PrintingUtilities.Type getType() {
        return PrintingUtilities.Type.DIRECT;
    }

    @Override
    public void parseData(JSONArray printData, PrintOptions options) throws JSONException, UnsupportedOperationException {
        if (tempFile == null) {
            try {
                tempFile = Files.createTempFile("printjob", ".tmp");
                tempFile.toFile().deleteOnExit();
                outputFileStream = FileUtils.openOutputStream(tempFile.toFile(), true);
            }
            catch(IOException e) {
                log.error("Failed to create temp file ", e);
                throw new UnsupportedOperationException(e);
            }
        }

        for(int i = 0; i < printData.length(); i++) {
            JSONObject data = printData.optJSONObject(i);
            if (data == null) { continue; }
            printReady = printData.optJSONObject(i).optBoolean("lastChunk", false);
            try {
                lastFormat = PrintingUtilities.Format.valueOf(data.optString("format", lastFormat.toString()).toUpperCase(Locale.ENGLISH));
                InputStream input = null;
                switch(lastFormat) {
                    case BASE64:
                        input = new Base64InputStream(new ByteArrayInputStream(data.getString("data").getBytes(Charsets.UTF_8)));
                        break;
                    case FILE:
                        input = new DataInputStream(new URL(data.getString("data")).openStream());
                        break;
                    case IMAGE:
                        //commands.append(getImageWrapper(cmd, opt).getImageCommand());
                        break;
                    case HEX:
                        input = new ByteArrayInputStream(ByteUtilities.hexStringToByteArray(data.getString("data")));
                        break;
                    case XML:
                        //commands.append(Base64.decodeBase64(FileUtilities.readXMLFile(cmd, opt.optString("xmlTag"))));
                        break;
                    case PLAIN:
                    default:
                        input = IOUtils.toInputStream(data.getString("data"));
                        break;
                }
                IOUtils.copy(input, outputFileStream);
                input.close();
                //old data will hang around and build up, defeating the purpose of streaming. To fix this, a GC event is suggested to the JRE.
                //this prevents memory spikes at the cost of cpu cycles. Note, system.gc is not a guaranteed operation
                System.gc();
            }
            catch(IOException e) {
                log.error("Parse data failed ", e);
                throw new UnsupportedOperationException(e);
            }
        }
    }

    @Override
    public void print(PrintOutput output, PrintOptions options) throws PrintException {
        PrintOptions.Raw rawOpts = this.options.getRawOptions();
        for(int i = 0; i < rawOpts.getCopies(); i++) {
            PrintRequestAttributeSet attributes = new HashPrintRequestAttributeSet();
            attributes.add(new JobName(this.options.getRawOptions().getJobName(Constants.RAW_PRINT), Locale.getDefault()));
            DocPrintJob printJob = this.output.getPrintService().createPrintJob();
            SimpleDoc doc;
            try {
                doc = new SimpleDoc(Files.newInputStream(tempFile), DocFlavor.INPUT_STREAM.AUTOSENSE, null);
                waitForPrint(printJob, doc, attributes);
            }
            catch(IOException e) {
                throw new PrintException(e);
            }
        }
        streamEOL = true;
    }

    public void init(PrintOutput output, PrintOptions options) {
        this.output = output;
        this.options = options;
    }

    public boolean isEOL() {
        return streamEOL;
    }

    public boolean isReady() {
        return printReady;
    }

    @Override
    public void cleanup() {
        try {
            outputFileStream.flush();
            outputFileStream.close();
            Files.deleteIfExists(tempFile);
        }
        catch(IOException e) {
            log.error("Print processor failed cleanup ", e);
        } finally {
            options = null;
            printReady = false;
            streamEOL = false;
            lastFormat = PrintingUtilities.Format.PLAIN;
            outputFileStream = null;
            tempFile = null;
        }
    }
}
