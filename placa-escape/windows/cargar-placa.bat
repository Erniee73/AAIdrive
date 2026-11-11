@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo.
echo  ==== Cargar el programa B48-Escape en la ESP32-C3 ====
echo.
echo  1. Conecta la placa al PC con un cable USB-C de datos.
echo  2. Si falla, desenchufala, manten pulsado BOOT, enchufala y suelta BOOT.
echo.
pause
esptool.exe --chip esp32c3 write_flash 0x0 b48-escape.bin
if errorlevel 1 (
  echo.
  echo  *** No se ha podido cargar. Prueba con BOOT pulsado al enchufar, u otro cable USB. ***
) else (
  echo.
  echo  *** Listo. Desenchufa y vuelve a enchufar la placa. ***
)
echo.
pause
