var wshShell = new ActiveXObject("WScript.Shell");
var wshEnv = wshShell.Environment("PROCESS");
WScript.Echo("Hello " + wshEnv("TITLE") + "! This is Javascript.");