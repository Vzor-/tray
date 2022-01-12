Set fso = CreateObject ("Scripting.FileSystemObject")
Set stdout = fso.GetStandardStream (1)
Set wshShell = CreateObject( "WScript.Shell" )
stdout.WriteLine "Hello " & wshShell.ExpandEnvironmentStrings( "%TITLE%" ) & "! This is Visual Basic Script"
