package com.englishDictionary.webServer.controllers;

import com.englishDictionary.config.Config;
import com.englishDictionary.utils.json.JsonHelper;
import com.englishDictionary.webServer.HttpServletRequest;
import com.englishDictionary.webServer.HttpServletResponse;
import com.englishDictionary.webServer.annotations.Controller;
import com.englishDictionary.webServer.annotations.RequestMapping;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

@Controller(url = "/comments/")
public class CommentsController {

    private static int BUFFER_SIZE = 2048;
    private byte[] buffer = new byte[BUFFER_SIZE];

    @RequestMapping(url = "load.html")
    public void loadComments(HttpServletRequest request, HttpServletResponse response) {
        String fileName = request.getParameter("fileName");
        File file = new File(Config.WORDS_FILES_FOLDER + fileName + "_comments.html");
        response.setContentType("application/json;charset=utf-8");
        try {
            CommentsListWords comments = new CommentsListWords();
            comments.setHaseComments(file.exists());
            comments.setCommentsFileName("http://localhost:8080/comments/commentsFile.html?fileName=" + fileName);
            comments.setNoCommentsMessage("There are not comments for current list of words");
            response.getOutputStream().write(JsonHelper.toJson(comments));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @RequestMapping(url = "commentsFile.html")
    public void sendCommentsFile(HttpServletRequest request, HttpServletResponse response) {
        try {
            String fullFileName = Config.WORDS_FILES_FOLDER + "\\" + request.getParameter("fileName") + "_comments.html";
            BufferedInputStream commentsFileIS = new BufferedInputStream(new FileInputStream(fullFileName));
            int lengthReadData;
            while ((lengthReadData = commentsFileIS.read(buffer, 0, buffer.length)) != -1) {
                response.getOutputStream().write(buffer, 0, lengthReadData);
            }
            commentsFileIS.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
