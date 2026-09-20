package com.tencent.qqmusic.third.api.contract;

import android.os.Bundle;

interface IQQMusicApiEventListener {
    void onEvent(String event, in Bundle data);
}
