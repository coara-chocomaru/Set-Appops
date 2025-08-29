#!/system/bin/sh

PACKAGES=$(pm list packages | cut -d':' -f2)

for pkg in $PACKAGES; do
    sh -c "appops set \"$pkg\" REQUEST_INSTALL_PACKAGES allow 2>/dev/null"
    sleep 1 
    sh -c "appops get \"$pkg\" REQUEST_INSTALL_PACKAGES 2>/dev/null"
    sleep 1 
done
