package com.dar.nclientv2.components.classes;

import android.content.Context;

import androidx.annotation.NonNull;

import com.dar.nclientv2.utility.LogUtility;

import org.acra.data.CrashReportData;
import org.acra.sender.ReportSender;
import org.json.JSONException;

import java.io.IOException;
import java.util.Map;

import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MySender implements ReportSender{
    @Override
    public void send(@NonNull Context context, @NonNull CrashReportData errorContent){
        Map<String,Object>m=errorContent.toMap();
        for (Map.Entry<String,Object> mm:m.entrySet()) {
            LogUtility.e(mm.getKey()+": "+mm.getValue());
        }
        try{
            LogUtility.d( errorContent.toJSON());
            RequestBody requestBody = new FormBody.Builder().add("json", errorContent.toJSON()).build();
            Request.Builder request = new Request.Builder().post(requestBody).url("https://alpastudios.000webhostapp.com/report.php");
            Response x = new OkHttpClient().newCall(request.build()).execute();
            LogUtility.d( x.code() + x.body().string());
        }catch(JSONException | IOException e){
            LogUtility.e( e.getLocalizedMessage(), e);
        }
    }
}
