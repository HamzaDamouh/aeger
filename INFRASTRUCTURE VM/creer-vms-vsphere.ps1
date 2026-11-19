<#
.SYNOPSIS
  Crée les VM AEGER dans vSphere à partir de vms.json (généré par generer.py) : CPU, mémoire, disque,
  cartes réseau VMXNET3 avec leur MAC fixe sur le bon groupe de ports, CD de configuration cloud-init.
  Les VM sont créées éteintes. Nécessite VMware PowerCLI.

  ATTENTION : script écrit sans accès à un vCenter, donc NON TESTÉ sur un vSphere réel.
  Le lancer d'abord pour une seule VM (-Seulement VM-EDGE-01) et vérifier le résultat dans le client vSphere.
  La procédure manuelle équivalente est dans ARCHITECTURE APPLICATIVE/AEGER_reseau_VM.md, section 4.4.

.EXAMPLE
  Install-Module VMware.PowerCLI -Scope CurrentUser
  Connect-VIServer vcenter.ecole.fr
  .\creer-vms-vsphere.ps1 -Modele ubuntu-22.04-cloud -Cluster "Cluster-TP" -Datastore "DS-TP" `
      -DossierIso "[DS-TP] aeger-seed" -Seulement VM-EDGE-01
#>
param(
  [Parameter(Mandatory)][string]$Modele,       # modèle créé à partir de l'OVA Ubuntu Server 22.04 cloud
  [Parameter(Mandatory)][string]$Datastore,    # datastore des disques des VM
  [Parameter(Mandatory)][string]$DossierIso,   # dossier des ISO cloud-init, ex. "[DS-TP] aeger-seed"
  [string]$Cluster,                            # cluster ...
  [string]$VMHost,                             # ... ou hôte ESXi
  [string]$Dossier,                            # dossier de VM (facultatif)
  [string[]]$Seulement                         # limiter à certaines VM
)
$ErrorActionPreference = 'Stop'
$vms = Get-Content (Join-Path $PSScriptRoot 'vms.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$modeleVM = Get-Template -Name $Modele
$ds = Get-Datastore -Name $Datastore
if ($Cluster) { $cible = @{ ResourcePool = (Get-Cluster -Name $Cluster) } }
elseif ($VMHost) { $cible = @{ VMHost = (Get-VMHost -Name $VMHost) } }
else { throw "Indiquer -Cluster ou -VMHost" }

# groupe de ports distribué (vSwitch distribué) ou standard
function Get-Reseau([string]$nom) {
  $pg = Get-VDPortgroup -Name $nom -ErrorAction SilentlyContinue
  if ($pg) { return @{ Portgroup = $pg } }
  return @{ NetworkName = $nom }
}

foreach ($v in $vms) {
  if ($Seulement -and ($Seulement -notcontains $v.name)) { continue }
  "=== $($v.name) : $($v.cpus) vCPU, $($v.ram) Mo, $($v.disk) Go"
  $p = @{ Name = $v.name; Template = $modeleVM; Datastore = $ds; DiskStorageFormat = 'Thin' } + $cible
  if ($Dossier) { $p.Location = Get-Folder -Name $Dossier -Type VM }
  $vm = New-VM @p
  $vm = Set-VM -VM $vm -NumCpu $v.cpus -MemoryMB $v.ram -Confirm:$false
  Get-HardDisk -VM $vm | Select-Object -First 1 | Set-HardDisk -CapacityGB $v.disk -Confirm:$false | Out-Null

  # cartes réseau : celles du modèle sont remplacées par celles de vms.json (MAC fixes, reconnues par Ubuntu)
  Get-NetworkAdapter -VM $vm | Remove-NetworkAdapter -Confirm:$false
  foreach ($n in $v.nics) {
    $reseau = Get-Reseau $n.portgroup
    New-NetworkAdapter -VM $vm @reseau -Type Vmxnet3 -MacAddress $n.mac -StartConnected -Confirm:$false | Out-Null
    "   carte $($n.name) -> $($n.portgroup) ($($n.mac))"
  }

  # CD de configuration cloud-init, connecté au démarrage
  $iso = "$DossierIso/$($v.iso)"
  $cd = Get-CDDrive -VM $vm
  if ($cd) { Set-CDDrive -CD $cd -IsoPath $iso -StartConnected:$true -Confirm:$false | Out-Null }
  else { New-CDDrive -VM $vm -IsoPath $iso -StartConnected -Confirm:$false | Out-Null }
  "   CD : $iso"
}
"VM créées (éteintes). Démarrer d'abord VM-EDGE-01 (passerelle), puis VM-COLD-01 (routeur) : Start-VM -VM VM-EDGE-01"
