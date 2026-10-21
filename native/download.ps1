param([Parameter(Mandatory)][string]$Url, [Parameter(Mandatory)][string]$Destination)
$ErrorActionPreference = 'Stop'
if (!([Uri]$Url).IsAbsoluteUri -or ([Uri]$Url).Scheme -ne 'https') { throw 'Downloads require HTTPS' }
Invoke-WebRequest -Uri $Url -OutFile $Destination
