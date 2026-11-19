# Crée un CD cloud-init (ISO, label "cidata", ISO9660 + Joliet) pour chaque dossier de _seed (généré par generer.py).
# Les fichiers _seed\<VM>.iso sont ensuite déposés sur un datastore vSphere (dossier aeger-seed).
param([string]$SeedRoot = "$PSScriptRoot\_seed")
$ErrorActionPreference = 'Stop'

if (-not ('AegerIsoWriter' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.Runtime.InteropServices;
using System.Runtime.InteropServices.ComTypes;
public static class AegerIsoWriter {
    public static void Save(object comStream, string path) {
        IStream s = (IStream)comStream;
        byte[] buf = new byte[65536];
        IntPtr pRead = Marshal.AllocHGlobal(4);
        try {
            using (FileStream fs = File.Create(path)) {
                while (true) {
                    s.Read(buf, buf.Length, pRead);
                    int n = Marshal.ReadInt32(pRead);
                    if (n <= 0) break;
                    fs.Write(buf, 0, n);
                }
            }
        } finally { Marshal.FreeHGlobal(pRead); }
    }
}
'@
}

foreach ($dir in Get-ChildItem -Path $SeedRoot -Directory) {
    $iso = Join-Path $SeedRoot ($dir.Name + '.iso')
    $img = New-Object -ComObject IMAPI2FS.MsftFileSystemImage
    $img.FileSystemsToCreate = 3      # ISO9660 + Joliet (Joliet garde les noms user-data, meta-data)
    $img.VolumeName = 'cidata'
    $img.Root.AddTree($dir.FullName, $false)
    $res = $img.CreateResultImage()
    [AegerIsoWriter]::Save($res.ImageStream, $iso)
    "{0,-12} -> {1} ({2} octets)" -f $dir.Name, $iso, (Get-Item $iso).Length
}
