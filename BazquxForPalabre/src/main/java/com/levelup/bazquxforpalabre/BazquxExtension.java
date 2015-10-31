package com.levelup.bazquxforpalabre;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.koushikdutta.async.future.FutureCallback;
import com.koushikdutta.ion.Ion;
import com.koushikdutta.ion.Response;
import com.levelup.palabre.api.ExtensionAccountInfo;
import com.levelup.palabre.api.ExtensionUpdateStatus;
import com.levelup.palabre.api.PalabreExtension;
import com.levelup.palabre.api.datamapping.Article;
import com.levelup.palabre.api.datamapping.Category;
import com.levelup.palabre.api.datamapping.Source;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Created by ludo on 02/06/15.
 */
public class BazquxExtension extends PalabreExtension {
    public static final String LATEST_ARTICLE_DATE = "LatestArticleDate";
    public static final String TOR_CAT_SUBSCRIPTION = "tor/-/label/Subscription";
    private static final String TAG = BazquxExtension.class.getSimpleName();
    SharedPreferences sharedPref;

    @Override
    protected void onUpdateData() {


        sharedPref = PreferenceManager.getDefaultSharedPreferences(this);
        final String authKey = sharedPref.getString(SharedPreferenceKeys.AUTH, null);
        Log.d("TOR", "Palabre asked that we refresh our data! " + authKey);

        publishUpdateStatus(new ExtensionUpdateStatus().start());
        // send information to Palabre for the progress bar
        publishUpdateStatus(new ExtensionUpdateStatus().progress(5));

        // get user profile
        Ion.with(this).load("https://www.bazqux.com/reader/api/0/user-info?output=json")
                .setHeader("Authorization", " GoogleLogin auth="+authKey)
                .setHeader("User-Agent", "Palabre")
                .asString()
                .setCallback(new FutureCallback<String>() {
                    @Override
                    public void onCompleted(Exception e, String resultS) {

                        Log.d("TOR", "user info " + resultS.toString());

                        JsonObject result = new JsonObject();
                        result = stringToJson(resultS);

                        //Gson gson = new Gson();
                        //target2 = gson.fromJson(result, JsonObject.class);

                        if (result != null) {
                            Log.d("TOR", result.toString());
                            String username = result.get("userName").getAsString();
                            Log.d("TOR", "user name " + username);
                            String email = result.get("userEmail").getAsString();
                            ExtensionAccountInfo account = new ExtensionAccountInfo();
                            account.accountName(username);
                            account.accountEmail(email);
                            publishAccountInfo(account);

                            publishUpdateStatus(new ExtensionUpdateStatus().progress(10));
                        }
                    }
                });

        // get the categories/tags
        fetchCategories(this, authKey, new OnCategoryAndSourceRefreshed() {
            @Override
            public void onFinished() {
                fetchArticles(authKey, 0);
            }

            @Override
            public void onFailure(Exception e) {
                endService(e);
            }

            @Override
            public void onProgressChanged(int progress) {
                publishUpdateStatus(new ExtensionUpdateStatus().progress(progress));
            }
        });
    }

    public static void fetchCategories(final Context context, final String authKey, final OnCategoryAndSourceRefreshed listener) {
        final List<Category> previousCategories = Category.getAll(context);

        Ion.with(context).load("https://www.bazqux.com/reader/api/0/tag/list?output=json")
                .setHeader("Authorization", " GoogleLogin auth="+authKey)
                .setHeader("User-Agent", "Palabre")
                .asString()
                .setCallback(new FutureCallback<String>() {
                    @Override
                    public void onCompleted(Exception e, String resultS) {

                        JsonObject result = new JsonObject();
                        result = stringToJson(resultS);
                        //Gson gson = new Gson();
                        //result = gson.fromJson(resultS, JsonObject.class);

                        //Log.d("TOR", "user info " + resultS.toString());

                        if (result != null) {
                            // We are getting the categories in an array
                            JsonArray tags = result.get("tags").getAsJsonArray();

                            // create a list of categories so we can save them all after parsing them
                            ArrayList<Category> categories = new ArrayList<Category>();

                            for (int i = 0; i < tags.size(); i++) {

                                String id = null;
                                try {
                                    id = tags.get(i).getAsJsonObject().get("id").getAsString();
                                } catch (NullPointerException e1) {
                                    if (listener != null) {
                                        listener.onFailure(e1);
                                        return;
                                    }
                                }
                                // we are getting the tags in this format: "user/-/label/Gaming"
                                // So we need to split the string to extract a user friendly category name
                                String[] splittedId = id.split("/");
                                String catName = splittedId[splittedId.length - 1];
                                if (id.equals("user/-/state/com.google/starred")) {
                                    continue;
                                }
                                Log.d("TOR", "Folder name: " + catName);

                                // Category is a Palabre object
                                Category category = new Category();
                                category.setUniqueId(id);
                                category.setTitle(catName);

                                // add it to the list
                                categories.add(category);

                            }

                            if (listener != null) {
                                listener.onProgressChanged(10);
                            }
                            // now save them using Palabre API.
                            Category.multipleSave(context, categories);

                            // remove the categories that the user might have deleted
                            final List<Category> catsToRemove = new ArrayList<Category>();
                            for (int c = 0; c < previousCategories.size(); c++) {
                                boolean found = false;
                                for (Category newCats : categories) {
                                    if (newCats.getUniqueId().equals(previousCategories.get(c).getUniqueId())) {
                                        found = true;
                                    }
                                }
                                if (!found) {
                                    Log.d("TOR", "Found cat to remove: " + previousCategories.get(c).getTitle());
                                    catsToRemove.add(previousCategories.get(c));
                                }
                            }

                            for (Category catToRemove : catsToRemove) {
                                catToRemove.delete(context);
                            }


                            if (listener != null) {
                                listener.onProgressChanged(20);
                            }

                            // now we can fetch the feed sources
                            fetchSources(context, authKey, listener);
                        } else {
                            if (listener != null) {
                                listener.onFailure(e);
                            }

                        }

                    }
                });
    }

    private static void fetchSources(final Context context, final String authKey, final OnCategoryAndSourceRefreshed listener) {

        final List<Category> categories = Category.getAll(context);
        final List<Source> previousSources = Source.getAll(context);

        Ion.with(context).load("https://www.bazqux.com/reader/api/0/subscription/list?output=json")
                .setHeader("Authorization", " GoogleLogin auth="+authKey)
                .setHeader("User-Agent", "Palabre")
                .asString()
                .setCallback(new FutureCallback<String>() {
                    @Override
                    public void onCompleted(Exception e, String resultS) {

                        JsonObject result = new JsonObject();
                        result = stringToJson(resultS);
                        //Gson gson = new Gson();
                        //result = gson.fromJson(resultS, JsonObject.class);

                        if (result != null) {


                            if (listener != null) {
                                listener.onProgressChanged(25);
                            }

                            // create a list of sources that we will save once everything has been parsed
                            ArrayList<Source> sources = new ArrayList<Source>();

                            JsonArray subscriptions = result.get("subscriptions").getAsJsonArray();
                            for (int i = 0; i < subscriptions.size(); i++) {
                                String id = null;
                                String title = null;
                                String iconUrl = null;
                                JsonArray subscribtionsCats = null;
                                try {
                                    id = subscriptions.get(i).getAsJsonObject().get("id").getAsString();
                                    title = subscriptions.get(i).getAsJsonObject().get("title").getAsString();
                                    //iconUrl = subscriptions.get(i).getAsJsonObject().get("iconUrl").getAsString();

                                    subscribtionsCats = subscriptions.get(i).getAsJsonObject().get("categories").getAsJsonArray();
                                } catch (NullPointerException e1) {
                                    if (listener != null) {
                                        listener.onFailure(e1);
                                        return;
                                    }
                                }

                                Source source = new Source();
                                source.setUniqueId(id);
                                source.setTitle(title);
                                source.setIconUrl(iconUrl);

                                // assign source to existing categories
                                for (int j = 0; j < subscribtionsCats.size(); j++) {
                                    String catId = subscribtionsCats.get(j).getAsJsonObject().get("id").getAsString();
                                    // find the existing category and use it to associate the source with it
                                    for (Category cat : categories) {
                                        if (cat.getUniqueId().equals(catId)) {
                                            source.getCategories().add(cat);
                                        }
                                    }
                                }

                                // We might need to handle subscriptions without a categories, we will create a "Subscriptions" category in this case
                                if (subscribtionsCats.size() == 0) {
                                    // there is no cat assigned, we will then create that cat (if needed)
                                    // and assign this source to it
                                    boolean requiredCatCreation = true;
                                    for (Category cat : categories) {
                                        if (cat.getUniqueId().equals(TOR_CAT_SUBSCRIPTION)) {
                                            source.getCategories().add(cat);
                                            requiredCatCreation = false;
                                        }
                                    }

                                    if (requiredCatCreation) {
                                        // create the cat
                                        Category category = new Category();
                                        category.setUniqueId(TOR_CAT_SUBSCRIPTION);
                                        category.setTitle("Subscriptions");
                                        category.save(context);
                                        source.getCategories().add(category);
                                    }
                                }

                                sources.add(source);
                            }

                            if (listener != null) {
                                listener.onProgressChanged(35);
                            }
                            // ask Palabre to save them
                            Source.multipleSave(context, sources);

                            // remove the sources that the user might have deleted. Children Articles will automatically be removed as well.
                            final List<Source> sourcesToRemove = new ArrayList<Source>();
                            for (int c = 0; c < previousSources.size(); c++) {
                                boolean found = false;
                                for (Source newSrc : sources) {
                                    if (newSrc.getUniqueId().equals(previousSources.get(c).getUniqueId())) {
                                        found = true;
                                    }
                                }
                                if (!found) {
                                    Log.d("TOR", "Found src to remove: " + previousSources.get(c).getTitle());
                                    sourcesToRemove.add(previousSources.get(c));
                                }
                            }

                            for (Source src : sourcesToRemove) {
                                src.delete(context);
                            }

                            if (listener != null) {
                                listener.onProgressChanged(40);
                            }

                            // then fetch the articles

                            if (listener != null) {
                                listener.onFinished();
                            }


                        } else {
                            if (listener != null) {
                                listener.onFailure(e);
                            }
                        }
                    }
                });

    }

    private void fetchArticles(final String authKey, final long continuationId) {
        if (BuildConfig.DEBUG) Log.d(TAG, "TimeTracking: fetchArticles");

        // retrieve the sources so we can assign articles to sources (if applicable)
        final List<Source> sources = Source.getAll(this);

        // we will save the most recent article date in milliseconds, then store it
        // so later we can query the API for newer articles only, on a future refresh
        final long[] latestArticleDate = {sharedPref.getLong(LATEST_ARTICLE_DATE, 0)};

        String query = "https://www.bazqux.com/reader/api/0/stream/contents?output=json&xt=user/-/state/com.google/read&n=1000";
        if (continuationId != 0) {
            // a continuation id means that the previous request had more data, and that we can query the continuation of our previous request
            query += "&c=" + continuationId;
        }
        if (latestArticleDate[0] == 0) {
            // this is our first refresh, we are going to get articles newer than 3 days
            long firstDate = System.currentTimeMillis() - (TimeUnit.DAYS.toMillis(3));
            query += "&ot=" + (firstDate/1000);
        } else {
            // we do an incremental refresh
            query += "&ot=" + (latestArticleDate[0] /1000);
        }
        Ion.with(this).load(query)
                .setHeader("Authorization", " GoogleLogin auth="+authKey)
                .setHeader("User-Agent", "Palabre")
                .asString()
                .setCallback(new FutureCallback<String>() {
                    @Override
                    public void onCompleted(Exception e, String resultS) {

                        //Log.d("BAZ", "Fetch articles " + resultS.toString());

                        JsonObject result = new JsonObject();
                        result = stringToJson(resultS);
                        //Gson gson = new Gson();
                        //result = gson.fromJson(resultS, JsonObject.class);



                        if (result != null) {

                            List<Article> allArticles = Article.getAll(BazquxExtension.this);

                            if (BuildConfig.DEBUG) Log.d(TAG, "TimeTracking: request done");

                            publishUpdateStatus(new ExtensionUpdateStatus().progress(50));

                            // create a list of articles that we will save once everything has been parsed
                            ArrayList<Article> articles = new ArrayList<Article>();

                            JsonArray items = result.get("items").getAsJsonArray();
                            for (int i = 0; i < items.size(); i++) {

                                // FIXME: duplicated code here and in
                                // starred items.
                                // FIXMEs below apply to fetchSaved() too.
                                String id = items.get(i).getAsJsonObject().get("id").getAsString();
                                long date = items.get(i).getAsJsonObject().get("crawlTimeMsec").getAsLong();

                                // FIXME: code below looks for incorrect ID
                                // and has O(n^2) complexity.
                                // Anyway it seems that Palabre doesn't
                                // duplicates articles with the same ID.
//                                 boolean found = false;
//                                 for (Article article : allArticles) {
//                                     if (article.getUniqueId().equals(id) && article.getDate().getTime() == date) {
//                                         found = true;
//                                         break;
//                                     }

//                                 }

//                                 if (found) {
//                                     continue;
//                                 }

                                // FIXME:
                                // Why not
                                //   item = items.get(i).getAsJsonObject()
                                //   article.setTitle(item.get("title").as...)
                                String summary = null;
                                String title = null;
                                String author = null;
                                String link = null;
                                String sourceUniqueId = null;
                                try {
                                    summary = items.get(i).getAsJsonObject().get("summary").getAsJsonObject().get("content").getAsString();
                                    title = items.get(i).getAsJsonObject().get("title").getAsString();
                                    author = items.get(i).getAsJsonObject().get("author").getAsString();
                                    if(items.get(i).getAsJsonObject().get("canonical").getAsJsonArray().size() != 0) {
                                        link = items.get(i).getAsJsonObject().get("canonical").getAsJsonArray().get(0).getAsJsonObject().get("href").getAsString();
                                    }

                                    sourceUniqueId = items.get(i).getAsJsonObject().get("origin").getAsJsonObject().get("streamId").getAsString();
                                } catch (NullPointerException e1) {
                                    endService(e1);
                                    return;
                                }

                                //Ugly wya to convert id tag:google.com,2005:reader/item/base64 to only long id
                                String modified = id.replaceAll("tag:google.com,2005:reader/item/", "");
                                Long decimal = Long.parseLong(modified, 16);

                                Article article = new Article();
                                article.setUniqueId(Long.toString(decimal));
                                article.setTitle(title);
                                article.setAuthor(author);
                                article.setLinkUrl(link);
                                article.setFullContent(summary);
                                article.setDate(new Date(date));

                                // always keep the most recent article date for later use
                                latestArticleDate[0] = Math.max(latestArticleDate[0], date);

                                // we need the Palabre internal id for the source
                                boolean sourceIdFound = false;
                                // FIXME: O(n^2). Ineffective when there are
                                // many feeds.
                                // Need HashMap of sources or something like this
                                // for O(1) checking.
                                for (Source source : sources) {
                                    if (source.getUniqueId().equals(sourceUniqueId)) {
                                        article.setSourceId(source.getId());
                                        sourceIdFound = true;
                                        break;
                                    }
                                }

                                if (!sourceIdFound)
                                    continue;


                                // find a picture within the article/summary using Jsoup.
                                // Some API/Services provides the picture directly, but that's not the case here
                                Document doc = Jsoup.parse(article.getFullContent());
                                String image = null;
                                for (Element el : doc.select("img")) {
                                    //Log.d("TOR", "Image: " + el.attr("src"));
                                    // filter a few pictures that has not relation with the article
                                    if (!el.attr("src").contains("feeds.feedburner.com") && !el.attr("src").contains("feedsportal.com")) {
                                        // use the first one
                                        if (image == null) {
                                            image = el.attr("src");
                                        }
                                    }
                                }

                                article.setImage(image);

                                articles.add(article);

                            }

                            if (BuildConfig.DEBUG) Log.d(TAG, "TimeTracking: queries generated");

                            publishUpdateStatus(new ExtensionUpdateStatus().progress(70));

                            // Save them into Palabre
                            Article.multipleSave(BazquxExtension.this, articles);

                            if (BuildConfig.DEBUG) Log.d(TAG, "TimeTracking: saves finished");

                            publishUpdateStatus(new ExtensionUpdateStatus().progress(75));

                            // now save a reference to the latest article date, save it, so on next refresh we will start from this date
                            sharedPref.edit().putLong(LATEST_ARTICLE_DATE, latestArticleDate[0]).apply();


                            JsonElement continuationObject = result.get("continuation");
                            if (continuationObject != null) {
                                // we can requery the continuation of our query
                                Log.d("TOR", "Continuation Id detected, requery " + result.get("continuation").getAsLong());
                                long newContinuationId = result.get("continuation").getAsLong();
                                fetchArticles(authKey, newContinuationId);
                            } else {
                                Log.d("TOR", "No Continuation");
                                fetchSaved(authKey);
                            }
                        } else {

                            endService(e);
                        }

                    }
                });

    }

    private void endService(Exception e) {
        if (e != null) {
            Log.w(TAG, e.getMessage(), e);
            String errorString;
            if (e instanceof UnknownHostException) {
                errorString = getResources().getString(R.string.refresh_error_connection);

            } else {
                errorString = getResources().getString(R.string.refresh_error, "\n" + e.getMessage());
            }

            publishUpdateStatus(new ExtensionUpdateStatus().fail(errorString));


        } else {
            publishUpdateStatus(new ExtensionUpdateStatus().stop());
        }
    }

    private void fetchSaved(final String authKey) {
        // retrieve the sources so we can assign articles to sources (if applicable)
        final List<Source> sources = Source.getAll(this);

        publishUpdateStatus(new ExtensionUpdateStatus().progress(77));

        Ion.with(this).load("https://www.bazqux.com/reader/api/0/stream/contents?output=json&s=user/-/state/com.google/starred&n=1000")
                .setHeader("Authorization", " GoogleLogin auth="+authKey)
                .setHeader("User-Agent", "Palabre")
                .asString()
                .setCallback(new FutureCallback<String>() {
                    @Override
                    public void onCompleted(Exception e, String resultS) {

                        JsonObject result = new JsonObject();
                        Gson gson = new Gson();
                        result = gson.fromJson(resultS, JsonObject.class);

                        Log.d("TOR", "Starred " + resultS.toString());

                        if (result != null) {
                            // create a list of articles that we will save once everything has been parsed
                            ArrayList<Article> articles = new ArrayList<Article>();

                            JsonElement itemsElement = result.get("items");

                            if (itemsElement != null) {
                                JsonArray items = result.get("items").getAsJsonArray();
                                for (int i = 0; i < items.size(); i++) {
                                    String id = null;
                                    String title = null;
                                    String author = null;
                                    String summary = null;
                                    String link = null;
                                    long date = 0;
                                    String sourceUniqueId = null;
                                    try {
                                        id = items.get(i).getAsJsonObject().get("id").getAsString();
                                        title = items.get(i).getAsJsonObject().get("title").getAsString();
                                        author = items.get(i).getAsJsonObject().get("author").getAsString();
                                        summary = items.get(i).getAsJsonObject().get("summary").getAsJsonObject().get("content").getAsString();
                                       // To do: check Canonical
                                        if(items.get(i).getAsJsonObject().get("canonical").getAsJsonArray().size() != 0) {
                                            link = items.get(i).getAsJsonObject().get("canonical").getAsJsonArray().get(0).getAsJsonObject().get("href").getAsString();
                                        }
                                        date = items.get(i).getAsJsonObject().get("crawlTimeMsec").getAsLong();

                                        if (items.get(i).getAsJsonObject().get("origin") != null) {
                                            sourceUniqueId = items.get(i).getAsJsonObject().get("origin").getAsJsonObject().get("streamId").getAsString();
                                        }
                                    } catch (NullPointerException e1) {
                                        endService(e1);
                                        return;
                                    }

                                    //Ugly wya to convert id tag:google.com,2005:reader/item/base64 to only long id
                                    String modified = id.replaceAll("tag:google.com,2005:reader/item/", "");
                                    Long decimal = Long.parseLong(modified, 16);

                                    Article starred = new Article();
                                    starred.setUniqueId(Long.toString(decimal));
                                    starred.setTitle(title);
                                    starred.setAuthor(author);
                                    starred.setLinkUrl(link);
                                    starred.setFullContent(summary);
                                    starred.setDate(new Date(date));

                                    // this is how we mark them as starred in Palabre
                                    starred.setSaved(true);


                                    // we need the Palabre internal id for the source
                                    boolean sourceIdFound = false;
                                    for (Source source : sources) {
                                        if (source.getUniqueId().equals(sourceUniqueId)) {
                                            starred.setSourceId(source.getId());
                                            sourceIdFound = true;
                                            break;
                                        }
                                    }

                                    if (!sourceIdFound)
                                        continue;


                                    // find a picture within the article/summary using Jsoup.
                                    // Some API/Services provides the picture directly, but that's not the case here
                                    Document doc = Jsoup.parse(starred.getFullContent());
                                    String image = null;
                                    for (Element el : doc.select("img")) {
                                        //Log.d("TOR", "Image: " + el.attr("src"));
                                        // filter a few pictures that has not relation with the article
                                        if (!el.attr("src").contains("feeds.feedburner.com") && !el.attr("src").contains("feedsportal.com")) {
                                            // use the first one
                                            if (image == null) {
                                                image = el.attr("src");
                                            }
                                        }
                                    }

                                    starred.setImage(image);

                                    articles.add(starred);

                                }

                                publishUpdateStatus(new ExtensionUpdateStatus().progress(82));

                                // Save them into Palabre
                                Article.multipleSave(BazquxExtension.this, articles);
                            }

                                publishUpdateStatus(new ExtensionUpdateStatus().progress(85));

                            fetchReads(authKey);
                        } else {
                            endService(e);
                        }

                    }
                });
    }

    private void fetchReads(final String authKey) {
        // we are going to query for the already read items, so if they are read
        // somewhere else (like on the old reader website), we can mark them as read local
        Log.d("Baz", "Fetching reads");
        final List<Article> articles = Article.getAll(this);


        // FIXME:
        //   Need to take in account that user can mark already read item
        //   as unread. So it worth to take all ids.
        // FIXME:
        //   Highly inneffectife O(n^2) algorithm as everywhere.
        //   Need HashMap or something like it to store read state
        //   and update articles state later.
        // we need to find the oldest article, and then we will ask Bazqux
        // for the read IDs since then. Then we will check if they are unread locally, and mark them as read in Palabre
        // Unfortunately the API does not have an API that could save us from doing so much processing, so we will have
        // to do a lot of iterations.
        long oldestArticle = System.currentTimeMillis();

        for (Article article : articles) {
            oldestArticle = Math.min(oldestArticle, article.getDate().getTime());
        }
        Log.d("Baz", "oldest article: "+ oldestArticle);

        Ion.with(this).load("https://www.bazqux.com/reader/api/0/stream/items/ids?output=json&s=user/-/state/com.google/read&n=10000&ot=" + (oldestArticle/1000))
                .setHeader("Authorization", " GoogleLogin auth="+authKey)
                .setHeader("User-Agent", "Palabre")
                .asString()
                .setCallback(new FutureCallback<String>() {
                    @Override
                    public void onCompleted(Exception e, String resultS) {

                        JsonObject result = new JsonObject();
                        Gson gson = new Gson();
                        result = gson.fromJson(resultS, JsonObject.class);

                        if (result != null) {
                            JsonArray items = result.get("itemRefs").getAsJsonArray();

                            for (int i = 0; i < items.size(); i++) {

                                String uniqueId = items.get(i).getAsJsonObject().get("id").getAsString();
                                //Log.d("TOR", "Local Article  " + items.get(i).getAsJsonObject().get("id").getAsString());
                                for (Article article : articles) {
                                    if (article.getUniqueId().contains(uniqueId) && !article.isRead()) {
                                        article.setRead(true);
                                        article.save(BazquxExtension.this);

                                    }
                                }

                            }
                            endService(null);
                        } else {
                            endService(e);
                        }
                    }
                });

    }

    @Override
    protected void onReadArticles(List<String> uniqueIdsList, boolean read) {
        // https://github.com/bazqux/api#updating-items
        Log.d("TOR", "Mark as read items");

        sharedPref = PreferenceManager.getDefaultSharedPreferences(this);
        String authKey = sharedPref.getString(SharedPreferenceKeys.AUTH, null);

        String items = "";
        for (String uniqueId : uniqueIdsList) {
            //Log.d("TOR", "Id: " + uniqueId);

            if (items.length() > 0) {
                // if there is more than one item, we need to append the other ids
                items += "&i=";
            }
            items += uniqueId;
        }
        // default action is to mark as read
        String action = "a";

        if (!read) {
            // We need to mark as unread
            action = "r";
        }


        Ion.with(this).load("https://www.bazqux.com/reader/api/0/edit-tag")
                .setHeader("Authorization", " GoogleLogin auth="+authKey)
                .setHeader("User-Agent", "Palabre")
                .setBodyParameter(action, "user/-/state/com.google/read")
                .setBodyParameter("i", items)
                .asString()
                .withResponse()
                .setCallback(new FutureCallback<Response<String>>() {
                    @Override
                    public void onCompleted(Exception e, Response<String> result) {

                        try {
                            Log.w("TOR", "Sending mark as read/unread: " + result.getHeaders().code());
                        } catch (Exception e1) {
                        }

                    }
                });

    }

    @Override
    protected void onReadArticlesBefore(String type, String uniqueId, long timestamp) {
        if (BuildConfig.DEBUG)
            Log.d(TAG, "onReadArticlesBefore for: " + type + " before " + new Date(timestamp) + " with uniqueId" + uniqueId);

        if (type.equals("all")) {
            uniqueId = "user%2F-%2Fstate%2Fcom.google%2Freading-list";
        }

        sharedPref = PreferenceManager.getDefaultSharedPreferences(this);
        String authKey = sharedPref.getString(SharedPreferenceKeys.AUTH, null);

        final long timestampNs = timestamp * 1000;
        Ion.with(this).load("https://www.bazqux.com/reader/api/0/mark-all-as-read")
                .setHeader("Authorization", "GoogleLogin auth=" + authKey)
                .setHeader("User-Agent", "Palabre")
                .setBodyParameter("s", uniqueId)
                .setBodyParameter("ts", String.valueOf(timestampNs))
                .asString()
                .withResponse()
                .setCallback(new FutureCallback<Response<String>>() {
                    @Override
                    public void onCompleted(Exception e, Response<String> result) {

                        try {
                            Log.w("TOR", "Sending mark as read before: " + result.getHeaders().code() +" => "+ result.getResult());
                        } catch (Exception e1) {
                        }

                    }
                });
//
    }

    @Override
    protected void onSavedArticles(List<String> uniqueIdsList, boolean saved) {
        // https://github.com/bazqux/api#updating-items
        Log.d("TOR", "Mark as saved items");

        sharedPref = PreferenceManager.getDefaultSharedPreferences(this);
        String authKey = sharedPref.getString(SharedPreferenceKeys.AUTH, null);

        String items = "";
        for (String uniqueId : uniqueIdsList) {

            if (items.length() > 0) {
                // if there is more than one item, we need to append the other ids
                items += "&i=";
            }
            items += uniqueId;
        }
        // default action is to mark as starred
        String action = "a";

        if (!saved) {
            // We need to mark as unstarred
            action = "r";
        }


        Ion.with(this).load("https://www.bazqux.com/reader/api/0/edit-tag")
                .setHeader("Authorization", " GoogleLogin auth="+authKey)
                .setHeader("User-Agent", "Palabre")
                .setBodyParameter(action, "user/-/state/com.google/starred")
                .setBodyParameter("i", items)
                .asString()
                .withResponse()
                .setCallback(new FutureCallback<Response<String>>() {
                    @Override
                    public void onCompleted(Exception e, Response<String> result) {

                        Log.w("TOR", "Sending mark as starred/unstarred: " + result.getHeaders().code());

                    }
                });

    }


    public interface OnCategoryAndSourceRefreshed {
        void onFinished();

        void onFailure(Exception e);

        void onProgressChanged(int progress);
    }


    private static JsonObject stringToJson (String s){

        JsonObject j = new JsonObject();
        Gson gson = new Gson();
        j = gson.fromJson(s, JsonObject.class);

        return j;
    }
}
