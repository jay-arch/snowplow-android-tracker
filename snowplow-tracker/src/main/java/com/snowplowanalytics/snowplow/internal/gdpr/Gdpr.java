package com.snowplowanalytics.snowplow.internal.gdpr;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.snowplowanalytics.snowplow.internal.constants.TrackerConstants;
import com.snowplowanalytics.snowplow.payload.SelfDescribingJson;
import com.snowplowanalytics.snowplow.internal.utils.Preconditions;
import com.snowplowanalytics.snowplow.util.Basis;

import java.util.HashMap;
import java.util.Map;

public class Gdpr {

    final Basis basisForProcessing;
    final String documentId;
    final String documentVersion;
    final String documentDescription;

    public Gdpr(@NonNull Basis basisForProcessing, @Nullable String documentId, @Nullable String documentVersion, @Nullable String documentDescription) {
        Preconditions.checkArgument(basisForProcessing != null, "GDPR basisForProcessiong can't be null.");
        this.basisForProcessing = basisForProcessing;
        this.documentId = documentId;
        this.documentVersion = documentVersion;
        this.documentDescription = documentDescription;
    }

    @NonNull
    public SelfDescribingJson getContext() {
        Map<String, Object> map = new HashMap<>();
        map.put("basisForProcessing", basisForProcessing.toString().toLowerCase());
        map.put("documentId", documentId);
        map.put("documentVersion", documentVersion);
        map.put("documentDescription", documentDescription);
        return new SelfDescribingJson(TrackerConstants.SCHEMA_GDPR, map);
    }
}