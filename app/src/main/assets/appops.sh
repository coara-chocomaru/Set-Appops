#!/system/bin/sh

set -u

LOGFILE="/sdcard/download/$(date +%Y%m%d_%H%M%S).txt"
touch "$LOGFILE" || { echo "Error: Failed to create logfile $LOGFILE"; exit 1; }

total=0
checked=0
skipped_no_permission=0
already_allowed=0
set_success=0
set_failed=0
failed_checks=0

declare -a already_allowed_pkgs
declare -a set_success_pkgs
declare -a set_failed_pkgs
declare -a skipped_no_permission_pkgs
declare -a failed_checks_pkgs

log() {
    echo "$@"
    echo "$@" >> "$LOGFILE"
}

PKG_LIST=$(pm list packages -3 2>/dev/null | cut -d: -f2)

if [ -z "$PKG_LIST" ]; then
    log "警告: 'pm list packages -3' でユーザーアプリが見つかりませんでした。全てのパッケージを対象にします。"
    PKG_LIST=$(pm list packages | cut -d: -f2)
fi

for pkg in $PKG_LIST; do
    total=$((total+1))

    requests_found=1
    if dumpsys package "$pkg" 2>/dev/null | grep -q "REQUEST_INSTALL_PACKAGES"; then
        requests_found=0
    else
        if pm dump "$pkg" 2>/dev/null | grep -q "REQUEST_INSTALL_PACKAGES"; then
            requests_found=0
        else
            requests_found=1
        fi
    fi

    if [ $requests_found -ne 0 ]; then
        skipped_no_permission=$((skipped_no_permission+1))
        skipped_no_permission_pkgs+=("$pkg")
        log "SKIP (no manifest permission) : $pkg"
        continue
    fi

    checked=$((checked+1))

    appops_out=$(appops get "$pkg" REQUEST_INSTALL_PACKAGES 2>/dev/null || true)
    if [ -z "$appops_out" ]; then
        failed_checks=$((failed_checks+1))
        failed_checks_pkgs+=("$pkg")
        log "WARN: appops の取得に失敗 : $pkg"
        continue
    fi

    echo "$appops_out" | tr '[:upper:]' '[:lower:]' | grep -qw "allow"
    if [ $? -eq 0 ]; then
        already_allowed=$((already_allowed+1))
        already_allowed_pkgs+=("$pkg")
        log "ALREADY allowed : $pkg"
        continue
    fi

    log "SET allow : $pkg"
    if appops set "$pkg" REQUEST_INSTALL_PACKAGES allow 2>/dev/null; then
        appops_out2=$(appops get "$pkg" REQUEST_INSTALL_PACKAGES 2>/dev/null || true)
        echo "$appops_out2" | tr '[:upper:]' '[:lower:]' | grep -qw "allow"
        if [ $? -eq 0 ]; then
            set_success=$((set_success+1))
            set_success_pkgs+=("$pkg")
            log "OK : $pkg is now allowed"
        else
            set_failed=$((set_failed+1))
            set_failed_pkgs+=("$pkg")
            log "FAIL: set succeeded but verification failed : $pkg"
        fi
    else
        set_failed=$((set_failed+1))
        set_failed_pkgs+=("$pkg")
        log "FAIL: appops set failed : $pkg"
    fi
done

log "-------------------------"
log "総パッケージ数: $total"
log "ManifestにREQUEST_INSTALL_PACKAGES存在するか確認: $checked"
log " - 設定済み: $already_allowed"
if [ ${#already_allowed_pkgs[@]} -gt 0 ]; then
    log "   設定済みパッケージ一覧:"
    for pkg in "${already_allowed_pkgs[@]}"; do
        log "     - $pkg"
    done
fi
log " - 設定成功: $set_success"
if [ ${#set_success_pkgs[@]} -gt 0 ]; then
    log "   設定成功パッケージ一覧:"
    for pkg in "${set_success_pkgs[@]}"; do
        log "     - $pkg"
    done
fi
log " - 設定失敗: $set_failed"
if [ ${#set_failed_pkgs[@]} -gt 0 ]; then
    log "   設定失敗パッケージ一覧:"
    for pkg in "${set_failed_pkgs[@]}"; do
        log "     - $pkg"
    done
fi
log "スキップ: Manifestに権限無し: $skipped_no_permission"
if [ ${#skipped_no_permission_pkgs[@]} -gt 0 ]; then
    log "   スキップ (Manifestに権限無し) パッケージ一覧:"
    for pkg in "${skipped_no_permission_pkgs[@]}"; do
        log "     - $pkg"
    done
fi
log "警告/確認できない: $failed_checks"
if [ ${#failed_checks_pkgs[@]} -gt 0 ]; then
    log "   警告/確認できないパッケージ一覧:"
    for pkg in "${failed_checks_pkgs[@]}"; do
        log "     - $pkg"
    done
fi
log "処理完了しました。アプリを終了してください。"
log "ログファイル: $LOGFILE"
