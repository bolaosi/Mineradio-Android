package com.mineradio.android.playback;

/** UI diagnostics use fixed codes only. Never include upstream exception text or signed URLs. */
public final class PlaybackFailure {
    private PlaybackFailure(){}
    public static String message(String code,boolean online){
        String detail;
        switch(code){
            case "ERROR_CODE_FAILED_RUNTIME_CHECK":detail="播放器内部状态异常，请重新播放；若仍出现请保留此错误码";break;
            case "MEDIA_TRACK_UNAVAILABLE":detail="官方未提供这首歌的音源，可能受版权、地区或会员权限限制";break;
            case "MEDIA_LOGIN_REQUIRED":detail="播放接口要求重新验证账号，请重新登录";break;
            case "MEDIA_API_CONNECTION_FAILED":detail="获取播放地址失败，请检查网络后重试";break;
            case "MEDIA_API_REQUEST_REJECTED":detail="播放地址请求被官方接口拒绝";break;
            case "MEDIA_NETWORK_TIMEOUT":detail="音频连接超时，请检查网络后重试";break;
            case "MEDIA_DNS_FAILED":detail="无法解析音频服务器，请检查网络或DNS";break;
            case "MEDIA_TLS_FAILED":detail="音频HTTPS连接失败，请检查网络与系统时间";break;
            case "MEDIA_HTTP_401":case "MEDIA_HTTP_403":detail="音源访问被拒绝，刷新链接后仍失败；请检查账号权限或网络";break;
            case "MEDIA_HTTP_404":case "MEDIA_HTTP_410":detail="该音源已失效，刷新链接后仍不可用";break;
            case "MEDIA_RESPONSE_NOT_AUDIO":detail="音频服务器返回了错误页面，未将其当作音频播放";break;
            case "MEDIA_HOST_REJECTED":detail="官方返回了尚未支持的音源主机，已停止加载";break;
            case "MEDIA_LOCAL_PERMISSION_LOST":detail="本地音频的读取授权已失效，请重新导入";break;
            case "MEDIA_INVALID_RANGE":case "MEDIA_RANGE_UNAVAILABLE":detail="音频服务器未正确响应进度读取请求";break;
            case "MEDIA_TRUNCATED":case "MEDIA_READ_FAILED":detail="音频传输中断，请检查网络或文件后重试";break;
            case "ERROR_CODE_DECODING_FAILED":case "ERROR_CODE_DECODING_FORMAT_UNSUPPORTED":
            case "ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED":case "ERROR_CODE_PARSING_CONTAINER_MALFORMED":
                detail="设备无法解码这段音频，请尝试标准音质或设备支持的格式";break;
            case "ERROR_CODE_AUDIO_TRACK_INIT_FAILED":case "ERROR_CODE_AUDIO_TRACK_WRITE_FAILED":
                detail="系统音频输出初始化或写入失败，请检查输出设备后重试";break;
            case "ERROR_CODE_IO_NO_PERMISSION":detail="系统拒绝读取音频，请重新选择文件或检查访问权限";break;
            case "ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED":detail="系统阻止了音源的明文连接";break;
            default:detail=online?"音频加载失败，点播放可重新获取链接并重试":"本地音频读取或播放失败，请重新导入";
        }
        return detail+" ["+(code!=null&&code.matches("[A-Z0-9_]{1,80}")?code:"MEDIA_UNKNOWN")+"]";
    }
}
