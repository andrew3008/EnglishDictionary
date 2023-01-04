package com.englishDictionary.servicesThirdParty.forvo;

import com.englishDictionary.config.Config;
import com.englishDictionary.webServer.HttpServletResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.node.ArrayNode;
import org.codehaus.jackson.node.ContainerNode;
import org.codehaus.jackson.node.ObjectNode;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

// https://api.forvo.com/login/
// 
public class Forvo {

    static final String RESOURCE_URL_FOR_DEMO_FORM = "https://api.forvo.com/demo";
    static final String RESOURCE_URL_WITH_API_KEY = "http://apifree.forvo.com/key/" + Config.FORVO_API_KEY + "/format/xml/action/word-pronunciations/word/%1s/language/%2s/order/rate-desc/format/json";
    static final String MESSAGE_LIMIT_REQUESTS_FINISHED = "[\"Limit\\/day reached.\"]";
    static final String MESSAGE_ACCOUNT_IS_DISSABLED = "[\"Account disabled.\"]";
    static final String MESSAGE_ACCOUNT_IS_EXPIRED = "[\"Account expired.\"]";
    static final int HOUR_RESET_LIMIT_REQUESTS = 22;
    static final int CACHE_CARDS_MAX_NUM_ITEMS = 80;
    static final int FORVO_CARD_ITEM_LIVE_EXPIRATION_HOURS = 2;
    static final int MILLIS_PER_DAY = 86400000; // Number mills per day = 24 * 60 * 60 * 1000

    private HttpServletResponse servletResponse;
    private static ForvoCardsCache cacheCards = new ForvoCardsCache(CACHE_CARDS_MAX_NUM_ITEMS, FORVO_CARD_ITEM_LIVE_EXPIRATION_HOURS);
    private static boolean isLimitRequestsFinished = false;
    private static boolean isAccountDisabled = false;
    private static boolean isAccountExpired = false;
    private static Calendar dateAchievLimitRequests;

    public List<ForvoCard> getForvoCards(String word, HttpServletResponse response) {
        servletResponse = response;

        List<ForvoCard> cardsFromCache = cacheCards.get(word);
        if (cardsFromCache != null) {
            return cardsFromCache;
        }

        /*if (isLimitRequestsFinished || isAccountDisabled || isAccountExpired) {
            Calendar currentDate = Calendar.getInstance();
            if ((dateAchievLimitRequests != null) &&
                    ((dateAchievLimitRequests.getTimeInMillis() - currentDate.getTimeInMillis() > MILLIS_PER_DAY) ||
                            (dateAchievLimitRequests.get(Calendar.DAY_OF_MONTH) != currentDate.get(Calendar.DAY_OF_MONTH) &&
                                    (dateAchievLimitRequests.get(Calendar.HOUR_OF_DAY) < HOUR_RESET_LIMIT_REQUESTS || currentDate.get(Calendar.HOUR_OF_DAY) > HOUR_RESET_LIMIT_REQUESTS)) ||
                            (dateAchievLimitRequests.get(Calendar.DAY_OF_MONTH) == currentDate.get(Calendar.DAY_OF_MONTH) &&
                                    (dateAchievLimitRequests.get(Calendar.HOUR_OF_DAY) < HOUR_RESET_LIMIT_REQUESTS && currentDate.get(Calendar.HOUR_OF_DAY) > HOUR_RESET_LIMIT_REQUESTS)))) {
                isLimitRequestsFinished = false;
                dateAchievLimitRequests = null;
            } else {*/
                return getForvoCardsFromDemoForm(word);
                //return Collections.emptyList();
            /*}
        }

        return getForvoCardsByAPIKey(word);*/
    }

    public List<ForvoCard> getForvoCardFromCache(String word) {
        return cacheCards.get(word);
    }

    public List<ForvoCard> getForvoCardsByAPIKey(String word) {
        HttpGet get = new HttpGet(String.format(RESOURCE_URL_WITH_API_KEY, word, "en"));
        HttpClient client = HttpClientBuilder.create().build();
        HttpResponse response = null;
        int code = -1;
        try {
            response = client.execute(get);
            // TODO: Make the handling of errors in centralized class
            code = response.getStatusLine().getStatusCode();
                    /*if (code >= 400) {
                        throw new RuntimeException(
								"Could not access protected resource. Server returned http code: "
										+ code);

					}*/

				/*} else {
                    throw new RuntimeException(
							"Could not regenerate access token");
				}*/

            /*String contentType = null;
            if (response.getEntity().getContentType() != null) {
                contentType = response.getEntity().getContentType().getValue();
            }
            if (contentType.contains("text/html; charset=UTF-8")) {*/

            String responseMessage = EntityUtils.toString(response.getEntity());
            if (MESSAGE_LIMIT_REQUESTS_FINISHED.equals(responseMessage)) {
                isLimitRequestsFinished = true;
            } else if (MESSAGE_ACCOUNT_IS_DISSABLED.equals(responseMessage)) {
                isAccountDisabled = true;
            } else if (MESSAGE_ACCOUNT_IS_EXPIRED.equals(responseMessage)) {
                isAccountExpired = true;
            }
            if (isLimitRequestsFinished || isAccountDisabled || isAccountExpired) {
                dateAchievLimitRequests = Calendar.getInstance();
                return Collections.<ForvoCard>emptyList();
                //return getForvoCardsFromDemoForm(word);
            }

            List<ForvoCard> responces = parseJSon(responseMessage);
            if (responces != null) {
                cacheCards.put(word, responces);
                return responces;
            }
        } catch (IOException e) {
            e.printStackTrace();
            servletResponse.setStatus(HttpResponseStatus.INTERNAL_SERVER_ERROR);
            servletResponse.setErrorMessage(e.getMessage());
        } finally {
            get.releaseConnection();
        }

        return Collections.<ForvoCard>emptyList();
    }

    public List<ForvoCard> getForvoCardsFromDemoForm(String word) {
        List<ForvoCard> cardsFromCache = cacheCards.get(word);
        if (cardsFromCache != null) {
            return cardsFromCache;
        }

        HttpPost post = new HttpPost(RESOURCE_URL_FOR_DEMO_FORM);
        HttpClient client = HttpClientBuilder.create().build();
        HttpResponse response;
        int code = -1;
        try {
            List<NameValuePair> params = new ArrayList<>();
            params.add(new BasicNameValuePair("action", "word-pronunciations"));
            params.add(new BasicNameValuePair("format", "json"));
            params.add(new BasicNameValuePair("word", word));
            params.add(new BasicNameValuePair("language", "en"));
            params.add(new BasicNameValuePair("username", ""));
            params.add(new BasicNameValuePair("sex", ""));
            params.add(new BasicNameValuePair("rate", ""));
            params.add(new BasicNameValuePair("order", "rate-desc"));
            params.add(new BasicNameValuePair("limit", ""));
            post.setEntity(new UrlEncodedFormEntity(params, Config.CHARSET));

            response = client.execute(post);
            //response = client.execute(post);
            //response = client.execute(post);
            code = response.getStatusLine().getStatusCode();
                    /*if (code >= 400) {
                        throw new RuntimeException(
								"Could not access protected resource. Server returned http code: "
										+ code);

					}*/

				/*} else {
                    throw new RuntimeException(
							"Could not regenerate access token");
				}*/

            List<ForvoCard> responces = handleResponse(response);
            if (responces != null) {
                cacheCards.put(word, responces);
                return responces;
            }
        } catch (IOException e) {
            e.printStackTrace();
            servletResponse.setStatus(HttpResponseStatus.INTERNAL_SERVER_ERROR);
            servletResponse.setErrorMessage(e.getMessage());
        } finally {
            post.releaseConnection();
        }

        return Collections.<ForvoCard>emptyList();
    }

    private List<ForvoCard> handleResponse(HttpResponse response) {
        String contentType = null;
        if (response.getEntity().getContentType() != null) {
            contentType = response.getEntity().getContentType().getValue();
        }

        if (contentType.contains("text/html; charset=UTF-8")) {
            String htmlPageResultsSearch = "";
            try {
                htmlPageResultsSearch = EntityUtils.toString(response.getEntity());
            } catch (IOException e) {
                e.printStackTrace();
            }

            Document document;
            try {
                document = Jsoup.parse(htmlPageResultsSearch);
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }

            Element results = document.getElementsByClass("intro").get(0);

            List<Node> resultsTopChild = results.childNodes().get(1).childNodes();
            String resJSon = resultsTopChild.toString(); //.replace("\n", "").replace("\t", "").replace(": ", ":");
            //.replace("&quot;", "\"");
            if (resJSon.equals(MESSAGE_LIMIT_REQUESTS_FINISHED)) {
                servletResponse.setStatus(HttpResponseStatus.INTERNAL_SERVER_ERROR);
                servletResponse.setErrorMessage("Limit day of requests on Forvo reached.");
            } else {
                return parseJSon(resJSon);
            }
        }

        return null;
    }

    private List<ForvoCard> parseJSon(String json) {
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode rootNode;
        try {
            rootNode = (ArrayNode)mapper.readTree(json);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }

        Iterator<JsonNode> rootNodeItems = rootNode.getElements();
        if (rootNodeItems.hasNext()) {
            JsonNode rootNodeItem = rootNodeItems.next();
            if (!rootNodeItem.isNull() && rootNodeItem.isContainerNode()) {
                ObjectNode containerNode = (ObjectNode) rootNodeItem;
                ArrayNode items = (ArrayNode) containerNode.get("items");
                Iterator<JsonNode> voiceCardNodes = items.getElements();

                List<ForvoCard> forvoResponses = new ArrayList<>();
                while (voiceCardNodes.hasNext()) {
                    JsonNode voiceCardNode = voiceCardNodes.next();
                    Iterator<String> fieldNames = voiceCardNode.getFieldNames();
                    ForvoCard forvoResponce = new ForvoCard();
                    while (fieldNames.hasNext()) {
                        String fieldName = fieldNames.next();
                        String fieldValue = voiceCardNode.get(fieldName).asText();
                        if (fieldName.equals("sex")) {
                            forvoResponce.setMale(fieldValue.equals("m"));
                        } else if (fieldName.equals("username")) {
                            forvoResponce.setUser(fieldValue);
                            /*String userNamePercent = "";
                            try {
                                userNamePercent = URLDecoder.decode(forvoResponce.getUser(), Config.CHARSET);
                            } catch (UnsupportedEncodingException e) {
                                e.printStackTrace();
                            }
                            forvoResponce.setUserProfile("http://www.forvo.com/user/" + userNamePercent + "/");*/
                        } else if (fieldName.equals("country")) {
                            forvoResponce.setCountry(fieldValue);
                            forvoResponce.setFlagFileName(Flags.getFlagCode(fieldValue));
                        } else if (fieldName.equals("num_votes")) {
                            forvoResponce.setTotalVotes(voiceCardNode.get(fieldName).asInt());
                        } else if (fieldName.equals("num_positive_votes")) {
                            forvoResponce.setPositiveVotes(voiceCardNode.get(fieldName).asInt());
                            forvoResponce.setNegativeVotes(forvoResponce.getTotalVotes() - forvoResponce.getPositiveVotes());
                        } else if (fieldName.equals("pathogg")) {
                            forvoResponce.setPathmp3(fieldValue);
                        }
                    }
                    forvoResponses.add(forvoResponce);
                }
                return forvoResponses;
            }
        }

        return null;
    }
}
