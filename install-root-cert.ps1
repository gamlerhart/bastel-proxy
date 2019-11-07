# Evaluate this script
If (-NOT ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole] "Administrator"))
{
   $arguments = "-NoProfile -ExecutionPolicy Unrestricted -File `"$PSScriptRoot\install-root-cert.ps1`""
   echo $arguments
   Start-Process powershell -Verb runAs -ArgumentList $arguments -Wait
   Break
}

function SetRegistry($RegPath, $KeyName, $Value, $RegType)
{
    if(Test-Path $RegPath)
    {
        New-ItemProperty -Path $RegPath -Name $KeyName -Value $Value -PropertyType $RegType -Force | Out-Null
    }
    else
    {
        New-Item -Path $RegPath -Force | Out-Null
        New-ItemProperty -Path $RegPath -Name $KeyName -Value $Value -PropertyType $RegType -Force | Out-Null
    }
}

echo "Installing CA Certificate (do-not-trust-bastel-proxy-root) to your trust store"
Import-Certificate -FilePath "$PSScriptRoot\root-cert.crt" -CertStoreLocation cert:\CurrentUser\Root | Out-Null
echo "Installed CA Certificate (do-not-trust-bastel-proxy-root) to your trust store"

# Chrome alread reads the Windows cert store.
# Firefox needs a reg key to do the same
echo "Enable Firefox to trust Windows Certificates"
SetRegistry 'HKCU:\Software\Policies\Mozilla\Firefox\Certificates' 'ImportEnterpriseRoots' 1 'DWORD'
echo "Enabled Firefox to trust Windows Certificates"

echo "If Firefox, restart it to access bastel proxy sites"


if (Test-Path env:JAVA_HOME) {
    $JavaKeyTool="$env:JAVA_HOME/bin/keytool.exe"
    if (Test-Path "$env:JAVA_HOME\jre\lib\security\cacerts"){
        &$JavaKeyTool -importcert -alias do-not-trust-bastel-proxy-root -keystore "$env:JAVA_HOME/jre/lib/security/cacerts" -storepass changeit -file "$PSScriptRoot\root-cert.crt" -noprompt
    } elseif(Test-Path "$env:JAVA_HOME\lib\security\cacerts"){
        &$JavaKeyTool -importcert -alias do-not-trust-bastel-proxy-root -keystore "$env:JAVA_HOME/lib/security/cacerts" -storepass changeit -file "$PSScriptRoot\root-cert.crt" -noprompt
    } else {
        echo "Could not find Java cert store. Did not import into JDK root store. \$JAVA_HOME not set"
    }
} else {
    echo "JAVA_HOME not defined. Skipped import to Java trust store"
}
echo "Certificates imported. Hit enter to complete"
pause

