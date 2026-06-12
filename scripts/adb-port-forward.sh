#!/usr/bin/env bash
# ------------------------------------------------------------------
# adb-port-forward.sh
#
# 投屏 / WebRTC 项目常用的 adb 端口转发脚本。
# 发送端在设备（AVD 或真机）上监听 0.0.0.0:8888（DEFAULT_PORT=8888），
# 接收端通过本机的 127.0.0.1:8888 即可访问到发送端的信令服务。
#
# 使用方式：
#     chmod +x adb-port-forward.sh
#
#     # 1) 看当前有哪些设备
#     ./adb-port-forward.sh devices
#
#     # 2) 基本场景（默认转发 8888，自动选择 USB 真机）
#     #    发送端在 AVD（emulator-5554）上，接收端是 USB 真机：
#     ./adb-port-forward.sh setup --sender emulator-5554 --receiver usb
#
#     # 3) 两个模拟器
#     ./adb-port-forward.sh setup --sender emulator-5554 --receiver emulator-5556
#
#     # 4) 只把某个设备的 8888 反向到本机
#     ./adb-port-forward.sh reverse emulator-5554 8888
#     ./adb-port-forward.sh forward emulator-5554 8888
#
#     # 5) 查看 / 清理
#     ./adb-port-forward.sh list
#     ./adb-port-forward.sh clear-all emulator-5554
# ------------------------------------------------------------------

set -euo pipefail

PORT=8888

log()  { printf '\033[1;36m%s\033[0m\n' "$*" >&2; }
warn() { printf '\033[1;33m[WARN]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[ERROR]\033[0m %s\n' "$*" >&2; exit 1; }

require_adb() {
    command -v adb >/dev/null 2>&1 || die "找不到 adb，请把 Android SDK platform-tools 加入 PATH 或在此脚本顶部显式 export ADB=..."
}

cmd_devices() {
    log "当前连接的设备："
    adb devices -l
}

cmd_list() {
    log "forward 映射："
    adb forward --list
    log "reverse 映射："
    adb reverse --list
}

cmd_reverse() {
    local device="${1:-}"
    local port="${2:-$PORT}"
    [[ -n "$device" ]] || die "用法：$0 reverse <device-serial> [port]"
    log "[$device] reverse tcp:$port -> tcp:$port"
    adb -s "$device" reverse "tcp:$port" "tcp:$port"
}

cmd_forward() {
    local device="${1:-}"
    local port="${2:-$PORT}"
    [[ -n "$device" ]] || die "用法：$0 forward <device-serial> [port]"
    log "[$device] forward tcp:$port -> tcp:$port"
    adb -s "$device" forward "tcp:$port" "tcp:$port"
}

cmd_clear_all() {
    local device="${1:-}"
    [[ -n "$device" ]] || die "用法：$0 clear-all <device-serial>"
    log "[$device] 清理全部 forward / reverse 映射"
    adb -s "$device" forward --remove-all || true
    adb -s "$device" reverse --remove-all || true
}

# setup: 一键完成常见场景的转发
#   --sender   发送端序列号（必须）
#   --receiver 接收端序列号 | usb | emulator-XXXX
cmd_setup() {
    local sender="" receiver="" port="$PORT"

    while [[ $# -gt 0 ]]; do
        case "$1" in
            --sender|-s)   sender="$2";   shift 2;;
            --receiver|-r) receiver="$2"; shift 2;;
            --port|-p)     port="$2";     shift 2;;
            *) warn "未知参数: $1"; shift;;
        esac
    done

    [[ -n "$sender" ]] || die "缺少 --sender，用 adb devices 查看序列号"
    [[ -n "$receiver" ]] || die "缺少 --receiver（usb / emulator-XXXX / 序列号）"

    # 1) 发送端：把设备上监听的 port 反向到"本机"的 port
    #    这样本机访问 127.0.0.1:port 等于访问设备内部的服务
    log "[发送端=$sender] 将本机 tcp:$port 反向到发送端内部 tcp:$port"
    adb -s "$sender" reverse "tcp:$port" "tcp:$port"

    # 2) 接收端：让"接收端设备"访问本机的 port 就像访问自己的 port
    #    这里再做一次反向，接收端内部 127.0.0.1:port -> 本机 port
    local rec_ser="$receiver"
    if [[ "$rec_ser" == "usb" ]]; then
        # -d 代表唯一的 USB 设备（多 USB 真机时请改用确切序列号）
        log "[接收端=USB 真机] reverse tcp:$port -> tcp:$port"
        adb -d reverse "tcp:$port" "tcp:$port"
    else
        log "[接收端=$rec_ser] reverse tcp:$port -> tcp:$port"
        adb -s "$rec_ser" reverse "tcp:$port" "tcp:$port"
    fi

    echo
    echo "✅ 完成。两端 App 连接地址都填：127.0.0.1:${port}"
    echo "   发送端若为 AVD，且接收端也是模拟器，请在接收端里填 10.0.2.2:${port}"
}

cmd_help() {
    cat <<'EOF'
用法:
  ./adb-port-forward.sh <命令> [参数]

命令:
  devices                         列出当前连接的设备
  list                            列出当前所有 forward / reverse 映射
  reverse <serial> [port]         reverse 端口映射（设备访问本机 -> 设备自己）
  forward <serial> [port]         forward 端口映射（本机访问设备 -> 设备）
  clear-all <serial>              清理指定设备的全部映射
  setup --sender <s> --receiver <r|usb> [--port 8888]
                                  一键完成常用投屏场景的转发
  help                            显示本帮助

示例:
  ./adb-port-forward.sh devices
  ./adb-port-forward.sh setup --sender emulator-5554 --receiver usb
  ./adb-port-forward.sh setup --sender emulator-5554 --receiver emulator-5556
  ./adb-port-forward.sh clear-all emulator-5554
EOF
}

main() {
    require_adb
    local sub="${1:-help}"; shift || true
    case "$sub" in
        devices)   cmd_devices "$@";;
        list)      cmd_list "$@";;
        reverse)   cmd_reverse "$@";;
        forward)   cmd_forward "$@";;
        clear-all) cmd_clear_all "$@";;
        setup)     cmd_setup "$@";;
        help|-h|--help) cmd_help;;
        *) echo "未知命令: $sub"; echo; cmd_help; exit 1;;
    esac
}

main "$@"
