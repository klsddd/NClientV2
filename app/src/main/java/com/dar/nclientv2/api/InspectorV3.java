package com.dar.nclientv2.api;

import android.content.Context;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import com.dar.nclientv2.api.components.Gallery;
import com.dar.nclientv2.api.components.GenericGallery;
import com.dar.nclientv2.api.components.Tag;
import com.dar.nclientv2.api.enums.ApiRequestType;
import com.dar.nclientv2.api.enums.Language;
import com.dar.nclientv2.api.enums.TagStatus;
import com.dar.nclientv2.api.local.LocalGallery;
import com.dar.nclientv2.async.database.Queries;
import com.dar.nclientv2.settings.Global;
import com.dar.nclientv2.settings.Login;
import com.dar.nclientv2.utility.LogUtility;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import okhttp3.Request;
import okhttp3.Response;

public class InspectorV3 extends Thread implements Parcelable {
    protected InspectorV3(Parcel in) {
        byPopular = in.readByte() != 0;
        custom = in.readByte() != 0;
        page = in.readInt();
        pageCount = in.readInt();
        id = in.readInt();
        query = in.readString();
        url = in.readString();
        requestType=ApiRequestType.values()[in.readByte()];
        ArrayList x=null;
        switch (GenericGallery.Type.values()[in.readByte()]){
            case LOCAL:x = in.createTypedArrayList(LocalGallery.CREATOR); break;
            case SIMPLE:x = in.createTypedArrayList(SimpleGallery.CREATOR);break;
            case COMPLETE:x = in.createTypedArrayList(Gallery.CREATOR);break;
        }
        galleries=(ArrayList<GenericGallery>)x;
        tags =new HashSet<>(in.createTypedArrayList(Tag.CREATOR));
    }

    public static final Creator<InspectorV3> CREATOR = new Creator<InspectorV3>() {
        @Override
        public InspectorV3 createFromParcel(Parcel in) {
            return new InspectorV3(in);
        }

        @Override
        public InspectorV3[] newArray(int size) {
            return new InspectorV3[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeByte((byte) (byPopular ? 1 : 0));
        dest.writeByte((byte) (custom ? 1 : 0));
        dest.writeInt(page);
        dest.writeInt(pageCount);
        dest.writeInt(id);
        dest.writeString(query);
        dest.writeString(url);
        dest.writeByte((byte) requestType.ordinal());
        if(galleries==null||galleries.size()==0)dest.writeByte((byte)GenericGallery.Type.SIMPLE.ordinal());
        else dest.writeByte((byte)galleries.get(0).getType().ordinal());
        dest.writeTypedList(galleries);
        dest.writeTypedList(new ArrayList<>(tags));
    }

    public interface InspectorResponse{
        void onSuccess(List<GenericGallery>galleries);
        void onFailure(Exception e);
        void onStart();
        void onEnd();
    }
    public static abstract class DefaultInspectorResponse implements InspectorResponse{
        @Override public void onStart() {}
        @Override public void onEnd() {}
        @Override public void onSuccess(List<GenericGallery> galleries) {}
        @Override public void onFailure(Exception e) {
            LogUtility.e(e.getLocalizedMessage(),e);
        }
    }

    private boolean byPopular,custom;
    private int page,pageCount=-1,id;
    private String query,url;
    private ApiRequestType requestType;
    private Set<Tag> tags;
    private List<GenericGallery> galleries=null;
    private InspectorResponse response;
    private WeakReference<Context> context;

    private InspectorV3(Context context,InspectorResponse response){
        initialize(context, response);
    }
    public void initialize(Context context,InspectorResponse response){
        this.response=response;
        this.context=new WeakReference<>(context);
    }

    public InspectorV3 cloneInspector(Context context,InspectorResponse response){
        InspectorV3 inspectorV3=new InspectorV3(context,response);
        inspectorV3.query=query;
        inspectorV3.url=url;
        inspectorV3.tags=tags;
        inspectorV3.requestType=requestType;
        inspectorV3.byPopular=byPopular;
        inspectorV3.pageCount=pageCount;
        inspectorV3.page=page;
        inspectorV3.id=id;
        inspectorV3.custom=custom;
        return inspectorV3;
    }

    public static InspectorV3 favoriteInspector(Context context,String query, int page, InspectorResponse response){
        InspectorV3 inspector=new InspectorV3(context,response);
        inspector.page=page;
        inspector.pageCount=0;
        inspector.query=query==null?"":query;
        inspector.requestType=ApiRequestType.FAVORITE;
        inspector.tags=new HashSet<>(1);
        inspector.createUrl();
        return inspector;
    }
    public static InspectorV3 randomInspector(Context context,InspectorResponse response){
        InspectorV3 inspector=new InspectorV3(context,response);
        inspector.requestType=ApiRequestType.RANDOM;
        inspector.createUrl();
        return inspector;
    }
    public static InspectorV3 galleryInspector(Context context,int id,InspectorResponse response){
        InspectorV3 inspector=new InspectorV3(context,response);
        inspector.id=id;
        inspector.requestType=ApiRequestType.BYSINGLE;
        inspector.createUrl();
        return inspector;
    }

    public static InspectorV3 searchInspector(Context context, String query, Collection<Tag> tags, int page, boolean byPopular, InspectorResponse response){
        InspectorV3 inspector=new InspectorV3(context,response);
        inspector.custom=tags!=null;
        inspector.tags=inspector.custom?new HashSet<>(tags):getDefaultTags();
        inspector.tags.addAll(getLanguageTags(Global.getOnlyLanguage()));
        inspector.page=page;
        inspector.pageCount=0;
        inspector.query=query==null?"":query;
        inspector.byPopular=byPopular;
        if(query==null||query.equals("")) {
            switch (inspector.tags.size()) {
                case 0:
                    inspector.requestType = ApiRequestType.BYALL;
                    break;
                case 1:
                    inspector.requestType = ApiRequestType.BYTAG;
                    break;
                default:
                    inspector.requestType = ApiRequestType.BYSEARCH;
            }
        }else inspector.requestType=ApiRequestType.BYSEARCH;
        inspector.createUrl();
        return inspector;
    }

    private void createUrl() {
        Tag t=null;
        StringBuilder builder=new StringBuilder("https://nhentai.net/");
        switch (requestType){
            case BYALL:
                builder.append("?page=").append(page);
                break;
            case RANDOM:
                builder.append("random/");
                break;
            case BYSINGLE:
                builder.append("g/").append(id);
                break;
            case FAVORITE:
                builder.append("favorites/");
                if(query!=null&&query.length()>0)builder.append("?q=").append(query).append('&');
                else builder.append('?');
                builder.append("page=").append(page);
                break;
            case BYTAG:
                for(Tag tt:tags)t=tt;
                builder.append(t.findTagString()).append('/')
                        .append(t.getName().replace(' ','-'));
                if(byPopular)builder.append("/popular");
                else builder.append('/');
                builder.append("?page=").append(page);
                break;
            case BYSEARCH:
                builder.append("search/?q=").append(query);
                for(Tag tt:tags){
                    if(builder.toString().contains(tt.toQueryTag(TagStatus.ACCEPTED)))continue;
                    builder.append('+').append(tt.toQueryTag());
                }
                builder.append("&page=").append(page);
                if(byPopular)builder.append("&sort=popular");
                break;

        }
        url=builder.toString().replace(' ','+');
        LogUtility.d("WWW: "+getBookmarkURL());
    }
    private String getBookmarkURL(){
        if(page<2)return url;
        else return url.substring(0,url.lastIndexOf('=')+1);
    }

    @NonNull
    public static Set<Tag> getDefaultTags(){
        Set<Tag> tags = new HashSet<>(Queries.TagTable.getAllStatus( TagStatus.ACCEPTED));
        tags.addAll(getLanguageTags(Global.getOnlyLanguage()));
        if(Global.removeAvoidedGalleries()) tags.addAll(Queries.TagTable.getAllStatus(TagStatus.AVOIDED));
        if(Login.isLogged())tags.addAll(Queries.TagTable.getAllOnlineBlacklisted());
        return tags;
    }
    public static Set<Tag> getLanguageTags(Language onlyLanguage) {
        Set<Tag>tags=new HashSet<>();
        if(onlyLanguage==null)return tags;
        switch (onlyLanguage){
            case ENGLISH:tags.add(Queries.TagTable.getTagById(12227));break;
            case JAPANESE:tags.add(Queries.TagTable.getTagById(6346));break;
            case CHINESE:tags.add(Queries.TagTable.getTagById(29963));break;
            case UNKNOWN:
                tags.add(Queries.TagTable.getTagById(12227));
                tags.add(Queries.TagTable.getTagById(6346));
                tags.add(Queries.TagTable.getTagById(29963));
                for(Tag t:tags)t.setStatus(TagStatus.AVOIDED);
                break;
        }
        return tags;
    }

    public void execute()throws IOException{
            Response response=Global.client.newCall(new Request.Builder().url(url).build()).execute();
            Document document= Jsoup.parse(response.body().byteStream(),"UTF-8","https://nhentai.net/");
            if(requestType==ApiRequestType.BYSINGLE||requestType==ApiRequestType.RANDOM)
                doSingle(document.body());
            else doSearch(document.body());
    }

    @Override
    public void run() {
        LogUtility.d("Starting download: "+url);
        if(response!=null)response.onStart();
        try {
            execute();
            if(response!=null)response.onSuccess(galleries);
        } catch (IOException e) {
            if(response!=null)response.onFailure(e);
        }
        if(response!=null)response.onEnd();
        LogUtility.d("Finished download: "+url);
    }
    private void doSingle(Element document) throws IOException {
        galleries=new ArrayList<>(1);
        //Elements xxxxxxx=document.getElementsByTag("script");
        String x=document.getElementsByTag("script").last().html();
        int s=x.indexOf("new N.gallery(");
        if(s<0)return;
        s+=14;
        x=x.substring(s,x.indexOf('\n',s)-2);
        Elements com=document.getElementById("comments").getElementsByClass("comment");
        Elements rel=document.getElementById("related-container").getElementsByClass("gallery");
        boolean isFavorite;
        try {
             isFavorite = document.getElementById("favorite").getElementsByTag("span").get(0).text().equals("Unfavorite");
        }catch (Exception e){
            LogUtility.e(e.getLocalizedMessage(),e);
            isFavorite=false;
        }
        LogUtility.d("is favorite? "+isFavorite);
        galleries.add(new Gallery(context.get(), x,com,rel,isFavorite));
    }

    private void doSearch(Element document) {
        Elements gal=document.getElementsByClass("gallery");
        galleries=new ArrayList<>(gal.size());
        for(Element e:gal)galleries.add(new SimpleGallery(context.get(),e));
        gal=document.getElementsByClass("last");
        pageCount=gal.size()==0?Math.max(1,page):findTotal(gal.last());
    }
    private int findTotal(Element e){
        String temp=e.attr("href");

        try {
            return Integer.parseInt(Uri.parse(temp).getQueryParameter("page"));
        }catch (Exception ignore){return 1;}
    }


    public void setByPopular(boolean byPopular) {
        this.byPopular = byPopular;
        createUrl();
    }

    public void setPage(int page) {
        this.page = page;
        createUrl();
    }

    public int getPage() {
        return page;
    }

    public boolean isByPopular() {
        return byPopular;
    }

    public List<GenericGallery> getGalleries() {
        return galleries;
    }

    public String getUrl() {
        return url;
    }

    public ApiRequestType getRequestType() {
        return requestType;
    }

    public int getPageCount() {
        return pageCount;
    }

    public boolean isCustom() {
        return custom;
    }

    public String getQuery() {
        return query;
    }

    public Tag getTag(){
        Tag t=null;
        for(Tag tt:tags)t=tt;
        return t;
    }
}
