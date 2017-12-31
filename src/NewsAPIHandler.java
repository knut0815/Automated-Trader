import javafx.scene.control.ProgressBar;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.*;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;

public class NewsAPIHandler {
    static final String INTRINIO_API_CALL = "https://api.intrinio.com/news?ticker=";
    static final String INTRINIO_CSV_CALL = "https://api.intrinio.com/news.csv?page_size=10000&ticker=";
    static private String INTRINIO_USERNAME;
    static private String INTRINIO_PASSWORD;
    static private final int PAGES = 0, ARTICLES = 1; //Indices for accessing JSON metadata
    static private DatabaseHandler dh;

    static public void authenticate(String username, String password){
        INTRINIO_USERNAME = username;
        INTRINIO_PASSWORD = password;

        Authenticator.setDefault(new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(INTRINIO_USERNAME,INTRINIO_PASSWORD.toCharArray());
            }
        });
    }

    static public void getHistoricNews(String stock, DatabaseHandler dh) throws IOException, SQLException, InterruptedException {
        if (isOverLimit()) return;

        int values[] = getCSVMetaData(stock);

        double pageSize = 10000; //TODO: Maybe make this variable if necessary

        double storedArticles = Integer.parseInt(dh.executeQuery("SELECT COUNT(*) FROM newsarticles WHERE Symbol='" + stock + "';").get(0));

        if (storedArticles == values[ARTICLES])
            return;

        int startPage = values[PAGES] - (int) Math.floor(storedArticles / pageSize);

        int i = startPage;

        while (i >= 1 && !isOverLimit())
            getCSVNews(stock, i--);
    }

    static public void initialise(DatabaseHandler nddh) {
        dh = nddh;
    }

    static public int getCurrentCalls() throws SQLException {
        ArrayList<String> sCalls = dh.executeQuery("SELECT Calls FROM apicalls WHERE Date = CURDATE() AND Name='INTRINIO';");

        int calls = 0;

        if(!sCalls.isEmpty())
            calls = Integer.parseInt(sCalls.get(0));

        return calls;
    }

    static public boolean isOverLimit(int callsToPerform) throws SQLException {
        int limit = Integer.parseInt(dh.executeQuery("SELECT DailyLimit FROM apimanagement WHERE Name='INTRINIO';").get(0));

        return (callsToPerform + getCurrentCalls()) == limit;
    }

    static public boolean isOverLimit() throws SQLException {
        return isOverLimit(0);
    }

    static public int[] getCSVMetaData(String stock) throws IOException, SQLException, InterruptedException {
        URL url = new URL(INTRINIO_CSV_CALL + stock);

        TimeUnit.MILLISECONDS.sleep(1000); // To prevent blocking

        URLConnection connect = url.openConnection();
        InputStreamReader isr = null;

        try {
            isr = new InputStreamReader(url.openStream());
        } catch (IOException e) {
            HttpURLConnection http = (HttpURLConnection) connect;
            if (http.getResponseCode() == 429)
                System.err.println("Too many requests"); //TODO: Make a GUI graphic that shows this has occurred

            ((HttpURLConnection) connect).disconnect();

            dh.executeCommand("INSERT INTO apicalls VALUES ('INTRINIO', CURDATE(), 500) ON DUPLICATE KEY UPDATE Calls = 500;"); //Incase another system uses this program, this database value doesn't get updated, in which case if an error occurs, mark the api as "limit reached"
        }

        if (isr == null)
            return new int[]{0, 0};

        BufferedReader br = new BufferedReader(isr);

        String curr;

        curr=br.readLine();

        String[] splitString = curr.split(",");

        dh.executeCommand("INSERT INTO apicalls VALUES('INTRINIO', CURDATE(), 1) ON DUPLICATE KEY UPDATE Calls = Calls +1;");

        int pages = Integer.parseInt(splitString[3].split(":")[1].trim());
        int articles = Integer.parseInt(splitString[0].split(":")[1].trim());

        int[] values = {pages,articles};

        return values;
    }

    static public void getCSVNews(String stock, int page) throws IOException, SQLException, InterruptedException {
        System.out.println("Getting headlines for " + stock + " (Page " + page + ")");
        URL url = new URL(INTRINIO_CSV_CALL + stock + "&page_number=" + page);

        TimeUnit.MILLISECONDS.sleep(1000);
        URLConnection connect = url.openConnection();
        InputStreamReader isr = null;

        try {
            isr = new InputStreamReader(url.openStream());
        } catch (IOException e) {
            HttpURLConnection http = (HttpURLConnection) connect;
            if (http.getResponseCode() == 429)
                System.err.println("Too many requests"); //TODO: Make a GUI graphic that shows this has occurred

            ((HttpURLConnection) connect).disconnect();

            dh.executeCommand("INSERT INTO apicalls VALUES ('INTRINIO', CURDATE(), 500) ON DUPLICATE KEY UPDATE Calls = 500;"); //Incase another system uses this program, this database value doesn't get updated, in which case if an error occurs, mark the api as "limit reached"
        }

        if (isr == null)
            return;

        BufferedReader br = new BufferedReader(isr);
        String curr;

        ArrayList<String> newsArray = new ArrayList<String>();

        for (int i = 0; i < 2; i++)  //Remove preamble
            br.readLine();

        while((curr = br.readLine())!=null)
            newsArray.add(curr);

        br.close();

        for(String news : newsArray){
            String[] splitNews = news.split(",");
            if(splitNews.length == 7) {
                String title = splitNews[3].replaceAll("'", "").replaceAll("\"", "");
                String summary = splitNews[6].replaceAll("'", "");
                String date = splitNews[4].replaceAll("'", "");
                String link = splitNews[5].replaceAll("'", "");
                date = date.split(" ")[0] + " " + date.split(" ")[1];

                String data = "'" + stock + "','" + title + "','" + summary + "','" + date + "','" + link + "'";

                if (!dh.executeQuery("SELECT * FROM newsarticles WHERE URL ='" + link + "' AND Symbol = '" + stock + "'").isEmpty())
                    break;

                System.out.println("Discovered News Article for " + stock + ": " + title);

                ArrayList<String> results = dh.executeQuery("SELECT * FROM newsarticles WHERE Headline = '" + title + "' AND Symbol = '" + stock + "'");
                String command;
                if (results.isEmpty())
                    command = "INSERT INTO newsarticles (Symbol, Headline,Description,Published,URL) VALUES (" + data + ");";
                else
                    command = "INSERT INTO newsarticles (Symbol, Headline,Description,Published,URL, Duplicate) VALUES (" + data + ", 1);";

                try {
                    dh.executeCommand(command);
                } catch (Exception e) {
                    e.printStackTrace();
                }

            }
        }
    }

    static public void getNews(String stock, int page) throws IOException, SQLException {

        URL url = new URL(INTRINIO_API_CALL + stock + "&page_number=" + page);

        if(page == 1)
            System.out.println("Downloading Latest News for " + stock + "...");
        else
            System.out.println("Downloading Historical News (Page " + page + ") for " + stock + "...");

        String doc;
        try (InputStream in = url.openStream()) {
            Scanner s = new Scanner(in).useDelimiter(("\\A"));
            doc = s.next();
        }

        dh.executeCommand("INSERT INTO apicalls VALUES('INTRINIO', CURDATE(), 1) ON DUPLICATE KEY UPDATE Calls = Calls +1;");

        try {
            JSONObject obj = new JSONObject(doc);
            JSONArray arr = obj.getJSONArray("data");
            String punctuationRemover = "'";

            for (int i = 0; i < arr.length(); i++) {
                JSONObject ob2 = (JSONObject) arr.get(i);
                String title = ob2.getString("title").replaceAll(punctuationRemover, "");
                String summary = ob2.getString("summary").replaceAll(punctuationRemover, "");
                String date = ob2.getString("publication_date").replaceAll(punctuationRemover, "");
                String link = ob2.getString("url").replaceAll(punctuationRemover, "");
                date = date.split(" ")[0] + " " + date.split(" ")[1];

                String data = "'" + stock + "','" + title + "','" + summary + "','" + date + "','" + link + "'";

                String query = "SELECT * FROM newsarticles WHERE headline = '" + title + "' AND Symbol = '" + stock + "'";

                ArrayList<String> results = dh.executeQuery(query);

                if(results.isEmpty()) {
                    System.out.println(title);
                    String command = "INSERT INTO newsarticles (Symbol, Headline,Description,Published,URL) VALUES (" + data + ");";

                    try {
                        dh.executeCommand(command);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    static public void getHistoricNews(ArrayList<String> stockList, ProgressBar pb) throws IOException, SQLException, InterruptedException {
        double i = 0, t = stockList.size() - 1;
        for (String symbol : stockList) {
            getHistoricNews(symbol, dh);
            Controller.updateProgress(i++, t, pb);
        }
    }
}