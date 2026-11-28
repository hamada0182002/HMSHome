package com.hms.home.dlna;

/** Service descriptions (SCPD) for the three UPnP services. */
final class Scpd {
    private Scpd() { }

    static final String SINK = "http-get:*:video/mp4:*,http-get:*:video/x-matroska:*,http-get:*:video/mpeg:*,http-get:*:video/x-msvideo:*,http-get:*:video/webm:*,http-get:*:video/quicktime:*,http-get:*:video/MP2T:*,"
            + "http-get:*:video/x-flv:*,http-get:*:video/3gpp:*,http-get:*:audio/mpeg:*,http-get:*:audio/mp4:*,http-get:*:audio/aac:*,http-get:*:audio/flac:*,http-get:*:audio/x-wav:*,http-get:*:audio/ogg:*,"
            + "http-get:*:application/vnd.apple.mpegurl:*,http-get:*:application/x-mpegURL:*,http-get:*:image/jpeg:*,http-get:*:image/png:*";

    private static final String HEAD = "<?xml version=\"1.0\"?><scpd xmlns=\"urn:schemas-upnp-org:service-1-0\"><specVersion><major>1</major><minor>0</minor></specVersion>";

    private static String arg(String n, String dir, String var) {
        return "<argument><name>" + n + "</name><direction>" + dir + "</direction><relatedStateVariable>" + var + "</relatedStateVariable></argument>";
    }

    private static String action(String name, String... args) {
        StringBuilder sb = new StringBuilder("<action><name>" + name + "</name><argumentList>");
        for (String a : args) sb.append(a);
        return sb.append("</argumentList></action>").toString();
    }

    private static String sv(String name, String type, boolean events) {
        return "<stateVariable sendEvents=\"" + (events ? "yes" : "no") + "\"><name>" + name + "</name><dataType>" + type + "</dataType></stateVariable>";
    }

    private static String varAllowed(String name, String... vals) {
        StringBuilder sb = new StringBuilder("<stateVariable sendEvents=\"no\"><name>" + name + "</name><dataType>string</dataType><allowedValueList>");
        for (String v : vals) sb.append("<allowedValue>").append(v).append("</allowedValue>");
        return sb.append("</allowedValueList></stateVariable>").toString();
    }

    private static final String I = "A_ARG_TYPE_InstanceID";

    static final String AVT = HEAD + "<actionList>"
            + action("SetAVTransportURI", arg("InstanceID", "in", I), arg("CurrentURI", "in", "AVTransportURI"), arg("CurrentURIMetaData", "in", "AVTransportURIMetaData"))
            + action("SetNextAVTransportURI", arg("InstanceID", "in", I), arg("NextURI", "in", "AVTransportURI"), arg("NextURIMetaData", "in", "AVTransportURIMetaData"))
            + action("GetMediaInfo", arg("InstanceID", "in", I), arg("NrTracks", "out", "NumberOfTracks"), arg("MediaDuration", "out", "CurrentMediaDuration"), arg("CurrentURI", "out", "AVTransportURI"),
                    arg("CurrentURIMetaData", "out", "AVTransportURIMetaData"), arg("NextURI", "out", "AVTransportURI"), arg("NextURIMetaData", "out", "AVTransportURIMetaData"),
                    arg("PlayMedium", "out", "PlaybackStorageMedium"), arg("RecordMedium", "out", "PlaybackStorageMedium"), arg("WriteStatus", "out", "PlaybackStorageMedium"))
            + action("GetTransportInfo", arg("InstanceID", "in", I), arg("CurrentTransportState", "out", "TransportState"), arg("CurrentTransportStatus", "out", "TransportStatus"), arg("CurrentSpeed", "out", "TransportPlaySpeed"))
            + action("GetPositionInfo", arg("InstanceID", "in", I), arg("Track", "out", "CurrentTrack"), arg("TrackDuration", "out", "CurrentTrackDuration"), arg("TrackMetaData", "out", "AVTransportURIMetaData"),
                    arg("TrackURI", "out", "AVTransportURI"), arg("RelTime", "out", "RelativeTimePosition"), arg("AbsTime", "out", "AbsoluteTimePosition"), arg("RelCount", "out", "RelativeCounterPosition"), arg("AbsCount", "out", "AbsoluteCounterPosition"))
            + action("GetDeviceCapabilities", arg("InstanceID", "in", I), arg("PlayMedia", "out", "PlaybackStorageMedium"), arg("RecMedia", "out", "PlaybackStorageMedium"), arg("RecQualityModes", "out", "PlaybackStorageMedium"))
            + action("GetTransportSettings", arg("InstanceID", "in", I), arg("PlayMode", "out", "CurrentPlayMode"), arg("RecQualityMode", "out", "PlaybackStorageMedium"))
            + action("Stop", arg("InstanceID", "in", I))
            + action("Play", arg("InstanceID", "in", I), arg("Speed", "in", "TransportPlaySpeed"))
            + action("Pause", arg("InstanceID", "in", I))
            + action("Seek", arg("InstanceID", "in", I), arg("Unit", "in", "A_ARG_TYPE_SeekMode"), arg("Target", "in", "A_ARG_TYPE_SeekTarget"))
            + action("Next", arg("InstanceID", "in", I))
            + action("Previous", arg("InstanceID", "in", I))
            + action("SetPlayMode", arg("InstanceID", "in", I), arg("NewPlayMode", "in", "CurrentPlayMode"))
            + action("GetCurrentTransportActions", arg("InstanceID", "in", I), arg("Actions", "out", "CurrentTransportActions"))
            + "</actionList><serviceStateTable>"
            + varAllowed("TransportState", "STOPPED", "PLAYING", "PAUSED_PLAYBACK", "TRANSITIONING", "NO_MEDIA_PRESENT")
            + varAllowed("TransportStatus", "OK", "ERROR_OCCURRED")
            + varAllowed("PlaybackStorageMedium", "NONE", "NETWORK", "NOT_IMPLEMENTED")
            + varAllowed("CurrentPlayMode", "NORMAL")
            + sv("TransportPlaySpeed", "string", false) + sv("NumberOfTracks", "ui4", false) + sv("CurrentTrack", "ui4", false)
            + sv("CurrentTrackDuration", "string", false) + sv("CurrentMediaDuration", "string", false)
            + sv("AVTransportURI", "string", false) + sv("AVTransportURIMetaData", "string", false)
            + sv("RelativeTimePosition", "string", false) + sv("AbsoluteTimePosition", "string", false)
            + sv("RelativeCounterPosition", "i4", false) + sv("AbsoluteCounterPosition", "i4", false)
            + sv("CurrentTransportActions", "string", false)
            + varAllowed("A_ARG_TYPE_SeekMode", "REL_TIME", "ABS_TIME", "TRACK_NR")
            + sv("A_ARG_TYPE_SeekTarget", "string", false) + sv(I, "ui4", false)
            + sv("LastChange", "string", true)
            + "</serviceStateTable></scpd>";

    static final String RC = HEAD + "<actionList>"
            + action("ListPresets", arg("InstanceID", "in", I), arg("CurrentPresetNameList", "out", "PresetNameList"))
            + action("SelectPreset", arg("InstanceID", "in", I), arg("PresetName", "in", "A_ARG_TYPE_PresetName"))
            + action("GetMute", arg("InstanceID", "in", I), arg("Channel", "in", "A_ARG_TYPE_Channel"), arg("CurrentMute", "out", "Mute"))
            + action("SetMute", arg("InstanceID", "in", I), arg("Channel", "in", "A_ARG_TYPE_Channel"), arg("DesiredMute", "in", "Mute"))
            + action("GetVolume", arg("InstanceID", "in", I), arg("Channel", "in", "A_ARG_TYPE_Channel"), arg("CurrentVolume", "out", "Volume"))
            + action("SetVolume", arg("InstanceID", "in", I), arg("Channel", "in", "A_ARG_TYPE_Channel"), arg("DesiredVolume", "in", "Volume"))
            + "</actionList><serviceStateTable>"
            + sv("PresetNameList", "string", false) + sv("Mute", "boolean", false)
            + "<stateVariable sendEvents=\"no\"><name>Volume</name><dataType>ui2</dataType><allowedValueRange><minimum>0</minimum><maximum>100</maximum><step>1</step></allowedValueRange></stateVariable>"
            + varAllowed("A_ARG_TYPE_Channel", "Master") + sv("A_ARG_TYPE_PresetName", "string", false) + sv(I, "ui4", false)
            + sv("LastChange", "string", true)
            + "</serviceStateTable></scpd>";

    static final String CM = HEAD + "<actionList>"
            + action("GetProtocolInfo", arg("Source", "out", "SourceProtocolInfo"), arg("Sink", "out", "SinkProtocolInfo"))
            + action("GetCurrentConnectionIDs", arg("ConnectionIDs", "out", "CurrentConnectionIDs"))
            + action("GetCurrentConnectionInfo", arg("ConnectionID", "in", "A_ARG_TYPE_ConnectionID"), arg("RcsID", "out", "A_ARG_TYPE_RcsID"), arg("AVTransportID", "out", "A_ARG_TYPE_AVTransportID"),
                    arg("ProtocolInfo", "out", "A_ARG_TYPE_ProtocolInfo"), arg("PeerConnectionManager", "out", "A_ARG_TYPE_ConnectionManager"), arg("PeerConnectionID", "out", "A_ARG_TYPE_ConnectionID"),
                    arg("Direction", "out", "A_ARG_TYPE_Direction"), arg("Status", "out", "A_ARG_TYPE_ConnectionStatus"))
            + "</actionList><serviceStateTable>"
            + sv("SourceProtocolInfo", "string", true) + sv("SinkProtocolInfo", "string", true) + sv("CurrentConnectionIDs", "string", true)
            + varAllowed("A_ARG_TYPE_ConnectionStatus", "OK", "Unknown") + sv("A_ARG_TYPE_ConnectionManager", "string", false) + varAllowed("A_ARG_TYPE_Direction", "Input", "Output")
            + sv("A_ARG_TYPE_ProtocolInfo", "string", false) + sv("A_ARG_TYPE_ConnectionID", "i4", false) + sv("A_ARG_TYPE_AVTransportID", "i4", false) + sv("A_ARG_TYPE_RcsID", "i4", false)
            + "</serviceStateTable></scpd>";
}
