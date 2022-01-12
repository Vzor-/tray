package qz.installer;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;
import org.apache.commons.io.FilenameUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;

public class WindowsScriptExecutor {
    protected static final Logger log = LoggerFactory.getLogger(WindowsScriptExecutor.class);
    public static void runPs1(Path script) throws IOException, InterruptedException {
        // PowerShell is not version 1.0 but the folder name was never changed *shrug*
        Path powershell = Paths.get(System.getenv("WINDIR"), "system32", "WindowsPowerShell", "v1.0" , "powershell.exe");
        if (!Files.exists(powershell)) throw new FileNotFoundException(powershell.toString());
        // Should use prop "AllSigned" instead of ByPass
        ProcessBuilder pb = new ProcessBuilder(powershell.toString(), "-ExecutionPolicy",  "ByPass", "-File", script.toString(), "-Force");

        runAndOutput(pb);
    }

    public static void runPy2(Path script) throws IOException, InterruptedException {
        String regPath = "SOFTWARE\\WOW6432Node\\Python\\PythonCore";
        String[] s = Advapi32Util.registryGetKeys(WinReg.HKEY_LOCAL_MACHINE, regPath);
        String location = (String)Advapi32Util.registryGetValue(WinReg.HKEY_LOCAL_MACHINE, regPath + "\\" + s[0] + "\\InstallPath", (String)null);
        Path python = Paths.get(location, "python.exe");
        if (!Files.exists(python, new LinkOption[0])) {
            throw new FileNotFoundException(python.toString());
        } else {
            ProcessBuilder pb = new ProcessBuilder(new String[]{python.toString(), script.toString()});
            runAndOutput(pb);
        }
    }

    public static void runPy3(Path script) throws IOException, InterruptedException {
        String regPath = "SOFTWARE\\Python\\PythonCore";
        String[] s = Advapi32Util.registryGetKeys(WinReg.HKEY_CURRENT_USER, regPath);
        String location = Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, regPath + "\\" + s[0] + "\\InstallPath", "ExecutablePath");
        Path python = Paths.get(location);
        if (!Files.exists(python, new LinkOption[0])) {
            throw new FileNotFoundException(python.toString());
        } else {
            ProcessBuilder pb = new ProcessBuilder(new String[]{python.toString(), script.toString()});
            runAndOutput(pb);
        }
    }

    public static void runVbs(Path script) throws IOException, InterruptedException {
        Path cscript = Paths.get(System.getenv("WINDIR"), "system32", "cscript.exe");
        if (!Files.exists(cscript, new LinkOption[0])) {
            throw new FileNotFoundException(cscript.toString());
        } else {
            ProcessBuilder pb = new ProcessBuilder(new String[]{cscript.toString(), script.toString()});
            runAndOutput(pb);
        }
    }

    public static void runJs(Path script) throws IOException, InterruptedException {
        Path cscript = Paths.get(System.getenv("WINDIR"), "system32", "cscript.exe");
        if (!Files.exists(cscript, new LinkOption[0])) {
            throw new FileNotFoundException(cscript.toString());
        } else {
            ProcessBuilder pb = new ProcessBuilder(new String[]{cscript.toString(), "//E:jscript", script.toString()});
            runAndOutput(pb);
        }
    }

    public static void runAndOutput(ProcessBuilder pb) throws IOException, InterruptedException {
        System.out.println(pb.command());

        final Process p = pb.start();
        p.waitFor();
        BufferedReader outputStream = new BufferedReader(new InputStreamReader(p.getInputStream()));
        BufferedReader errorStream = new BufferedReader(new InputStreamReader(p.getErrorStream()));
        String line;
        StringBuilder sb = new StringBuilder();
        while((line=outputStream.readLine())!=null) sb.append(line);
        if (!sb.toString().trim().isEmpty()) log.warn("result: " + sb);
        sb = new StringBuilder();
        while((line=errorStream.readLine())!=null) sb.append(line);
        if (!sb.toString().trim().isEmpty()) log.warn("error: " + sb);
        p.destroy();
    }

    public static void run(Path script) throws IOException, InterruptedException {
        if(Files.isDirectory(script)) return;
        String type = FilenameUtils.getExtension(script.toString()).toLowerCase();
        switch(type) {
            case "ps1":
                runPs1(script);
                break;
            case "py":
            case "py3":
                runPy3(script);
                break;
            case "py2":
                runPy2(script);
                break;
            case "vbs":
                runVbs(script);
                break;
            case "js":
                runJs(script);
                break;
            default:
                log.error("The script file extension \"{}\" is unsupported for this operating system", type);
                break;
        }
    }
}
