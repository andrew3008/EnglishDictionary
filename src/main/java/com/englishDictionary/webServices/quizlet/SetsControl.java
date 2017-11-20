package com.englishDictionary.webServices.quizlet;

import com.englishDictionary.config.Config;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.apache.oltu.oauth2.common.OAuth;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.map.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.String;
import java.util.*;

public class SetsControl {

    public static List<String> getAllSetsID(String accessToken) {
        String response = getProtectedResource(accessToken, "https://api.quizlet.com/2.0/users/andrew30124?whitespace=1");

        ObjectMapper mapper = new ObjectMapper();
        JsonNode node;
        try {
            node = mapper.readTree(response);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }

        List<String> setsID = new ArrayList<>();
        node = node.get("sets");
        Iterator<JsonNode> nodeSets = node.getElements();
        while (nodeSets.hasNext()) {
            JsonNode nodeSet = nodeSets.next();
            Iterator<String> fieldNames = nodeSet.getFieldNames();
            while (fieldNames.hasNext()) {
                String fieldName = fieldNames.next();
                if (fieldName.equals("id")) {
                    setsID.add(nodeSet.get(fieldName).asText());
                }
            }
        }
        return setsID;
    }

    public static void deleteAllSets(String accessToken) {
        List<String> setsID = getAllSetsID(accessToken);
        System.out.println("setsID writedBytes: " + setsID.size());
        for (String setID : setsID) {
            System.out.println("setID:" + setID);
        }

        for (String setID : setsID) {
            //String accessToken1 = Auth2Client.getAccessToken(code);
            System.out.println("Before delete");
            deleteProtectedResourse(accessToken, "https://api.quizlet.com/2.0/sets/" + setID);
            System.out.println("After delete");
        }
    }

    public static void exportSet(String accessToken, String setName, String fileName) {
        //createProtectedResourse(accessToken, "https://api.quizlet.com/2.0/sets", setName, fileName);
        createProtectedResourse(accessToken, "https://api.quizlet.com/2.0/sets", "CurrentSet", fileName);
    }

    // -------------------------------------------------------------------------------

    private static HttpEntity getMapEntity(Map<String, String> mapParams, String charset) throws UnsupportedEncodingException {
        List<NameValuePair> params = new ArrayList<>();
        System.out.println("Params entries: ");
        for (Map.Entry<String, String> entry : mapParams.entrySet()) {
            System.out.println("key: " + entry.getKey() + ", value:" + entry.getValue());
            params.add(new BasicNameValuePair(entry.getKey(), entry.getValue()));
        }
        return new UrlEncodedFormEntity(params, charset);
    }

    private static void readTermsFromFileListWord(String fileName, List<NameValuePair> params) {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode nodeRoot;
        try {
            String fullFileName;
            if (Config.FILE_NAME_OF_WORDS_FROM_EXCEL_ALIAS.equals(fileName)) {
                fullFileName = Config.getFileNameOfWordsFromExcel();
            } else {
                fullFileName = Config.WORDS_FILES_FOLDER + "\\" + fileName + ".json";
            }
            nodeRoot = mapper.readTree(new File(fullFileName));
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        String wordName = null;
        Iterator<JsonNode> nodeSets = nodeRoot.getElements();
        while (nodeSets.hasNext()) {
            JsonNode nodeSet = nodeSets.next();
            Iterator<JsonNode> nodeTerms = nodeSet.getElements();
            while (nodeTerms.hasNext()) {
                JsonNode nodeTerm = nodeTerms.next();
                Iterator<String> fieldNames = nodeTerm.getFieldNames();
                while (fieldNames.hasNext()) {
                    String fieldName = fieldNames.next();
                    if ("Word".equals(fieldName)) {
                        wordName = nodeTerm.get(fieldName).asText().trim();
                        if (wordName.isEmpty()) {
                            wordName = null;
                        } else {
                            params.add(new BasicNameValuePair("terms[]", wordName));
                        }
                    } else if ((wordName != null) && "Translation".equals(fieldName)) {
                        params.add(new BasicNameValuePair("definitions[]", nodeTerm.get(fieldName).asText()));
                        wordName = null;
                    }
                }
            }
        }
    }

    public static void createProtectedResourse(String accessToken, String resourceURL, String setName, String fileName) {
        HttpPost post = new HttpPost(resourceURL);
        HttpClient client = HttpClientBuilder.create().build();
        HttpResponse response = null;
        int code = -1;
        try {
            post.addHeader(OAuth.HeaderType.AUTHORIZATION, getAuthorizationHeaderForAccessToken(accessToken));
            post.addHeader("Accept", "text/plain, */*; q=0.01");
            post.addHeader("Accept-Encoding", "gzip, deflate, br");
            post.addHeader("Accept-Language", "en-US,en;q=0.8");
            post.addHeader(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded; charset=UTF-8");

            List<NameValuePair> params = new ArrayList<>();
            params.add(new BasicNameValuePair("whitespace", "true"));
            params.add(new BasicNameValuePair("title", setName));
            params.add(new BasicNameValuePair("lang_terms", "en"));
            params.add(new BasicNameValuePair("lang_definitions", "ru"));
            readTermsFromFileListWord(fileName, params);
            post.setEntity(new UrlEncodedFormEntity(params, "UTF-8"));

            response = client.execute(post);
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

            //return handleResponse(response);
            System.out.println("[Post] response:[" + response + "]");

        } catch (ClientProtocolException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } finally {
            post.releaseConnection();
        }
    }

    public static void deleteProtectedResourse(String accessToken, String resourceURL) {
        HttpDelete del = new HttpDelete(resourceURL);
        HttpClient client = HttpClientBuilder.create().build();
        HttpResponse response = null;
        int code = -1;
        try {
            del.addHeader(OAuth.HeaderType.AUTHORIZATION, getAuthorizationHeaderForAccessToken(accessToken));

            response = client.execute(del);
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

            //return handleResponse(response);
            System.out.println("[Delete] response:[" + response + "]");
        } catch (ClientProtocolException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } finally {
            del.releaseConnection();
        }
    }

    public static String getProtectedResource(String accessToken, String resourceURL) {
        HttpGet get = new HttpGet(resourceURL);
        HttpClient client = HttpClientBuilder.create().build();
        HttpResponse response = null;
        int code = -1;
        try {
            get.addHeader(OAuth.HeaderType.AUTHORIZATION, getAuthorizationHeaderForAccessToken(accessToken));
            response = client.execute(get);
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

            return handleResponse(response);

        } catch (ClientProtocolException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } finally {
            get.releaseConnection();
        }

        return null;
    }

    private static String getAuthorizationHeaderForAccessToken(String accessToken) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("accessToken", accessToken);
        return org.apache.oltu.oauth2.common.utils.OAuthUtils.encodeAuthorizationBearerHeader(parameters);
    }

    public static String handleResponse(HttpResponse response) {
        String contentType = null;
        if (response.getEntity().getContentType() != null) {
            contentType = response.getEntity().getContentType().getValue();
            System.out.println("[OAuthUtils] handleResponse:[" + contentType + "]");
        }
        if (contentType.contains(OAuth.ContentType.JSON)) {
            return handleJsonResponse(response);
        } /*else if (contentType.contains(OAuthConstants.URL_ENCODED_CONTENT)) {
			return handleURLEncodedResponse(response);
		} else if (contentType.contains(OAuthConstants.XML_CONTENT)) {
			return handleXMLResponse(response);
		}*/
        /*else if (contentType.contains(OAuthConstants.HTML_CONTENT)) {
            System.out.println("[OAuthUtils] handleResponse " + response.toString());

            // Unsupported Content type
            throw new RuntimeException(
                    "Cannot handle "
                            + contentType
                            + " content type. Supported content types include JSON, XML and URLEncoded");
        }*/
        else {
            // Unsupported Content type
            throw new RuntimeException(
                    "Cannot handle "
                            + contentType
                            + " content type. Supported content types include JSON, XML and URLEncoded");
        }
    }

    private static String handleJsonResponse(HttpResponse response) {
        try {
            String result = EntityUtils.toString(response.getEntity());
            System.out.println("JsonResponse: [" + result);
            return result;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }
}
