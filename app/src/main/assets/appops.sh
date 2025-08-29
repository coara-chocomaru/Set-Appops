#!/system/bin/sh

PACKAGES=$(pm list packages | cut -d':' -f2)

for pkg in $PACKAGES; do
    appops set "$pkg" REQUEST_INSTALL_PACKAGES allow 2>/dev/null
    appops get "$pkg" REQUEST_INSTALL_PACKAGES 2>/dev/null
done

echo "処理が完了しました！ アプリを終了してください"
