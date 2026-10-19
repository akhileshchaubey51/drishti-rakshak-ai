$ErrorActionPreference = 'Stop'
$assets = Join-Path $PSScriptRoot 'app/src/main/assets'
New-Item -ItemType Directory -Force $assets | Out-Null
$url = 'https://tfhub.dev/tensorflow/lite-model/efficientdet/lite0/detection/metadata/1?lite-format=tflite'
$out = Join-Path $assets 'efficientdet-lite0.tflite'
Invoke-WebRequest -Uri $url -OutFile $out
Write-Host "Downloaded model to $out"
Write-Host "If the file is suspiciously small or HTML, use the official TensorFlow Lite sample link in README.md instead."
