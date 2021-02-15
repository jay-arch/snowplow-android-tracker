package com.snowplowanalytics.snowplow.globalcontexts;

import androidx.annotation.NonNull;

import com.snowplowanalytics.snowplow.internal.tracker.InspectableEvent;

public abstract class FunctionalFilter {

    public abstract boolean apply(@NonNull InspectableEvent event);
}
