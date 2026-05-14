$ErrorActionPreference = 'Stop'

param(
    [string]$Profile = 'dev'
)

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$jarPath = Join-Path $scriptDir "target\mysql-schema-mcp-$Profile-0.1.0.jar"
$configPath = Join-Path $scriptDir "src\main\resources\application-$Profile.yml"

if (-not (Test-Path $jarPath)) {
    throw "Jar not found: $jarPath. Run mvn clean package first."
}

if (-not (Test-Path $configPath)) {
    throw "Config file not found: $configPath"
}

& java -jar $jarPath --profile $Profile
