import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

interface StoppableRunnable extends Runnable {
    void stop();
}

public class NicoPlayer {
    public static URI getNicoURL() throws URISyntaxException {
        //TODO: Should implement ability to generate URL from video ID
        Scanner scanner = new Scanner(System.in);
        System.out.print("Enter the video to download: ");
        String input = scanner.nextLine();
        return new URI(input);
    }

    public static JSONObject parseInitialWatchData(String content) {
        Document doc = Jsoup.parse(content);
        Element initialWatchData = doc.getElementById("js-initial-watch-data");
        String APIdata = initialWatchData.attr("data-api-data");
        return new JSONObject(APIdata);
    }

    public static JSONObject getDMCpayload(JSONObject APIdata) {
        JSONObject data = APIdata
                .getJSONObject("video")
                .getJSONObject("dmcInfo")
                .getJSONObject("session_api");
        return new JSONObject()
                .put("session", new JSONObject()
                        .put("recipe_id", data.getString("recipe_id"))
                        .put("content_id", data.getString("content_id"))
                        .put("content_type", "movie")
                        .put("content_src_id_sets", new JSONArray()
                                .put(new JSONObject()
                                        .put("content_src_ids", new JSONArray()
                                                .put(new JSONObject()
                                                        .put("src_id_to_mux", new JSONObject()
                                                                .put("video_src_ids", new JSONArray()
                                                                        .put(data.getJSONArray("videos").getString(0))
                                                                )
                                                                .put("audio_src_ids", new JSONArray()
                                                                        .put(data.getJSONArray("audios").getString(0))
                                                                )
                                                        )
                                                )
                                        )
                                )
                        )
                        .put("timing_constraint", "unlimited")
                        .put("keep_method", new JSONObject()
                                .put("heartbeat", new JSONObject()
                                        .put("lifetime", data.getInt("heartbeat_lifetime"))
                                )
                        )
                        .put("protocol", new JSONObject()
                                .put("name", "http")
                                .put("parameters", new JSONObject().
                                        put("http_parameters", new JSONObject()
                                                .put("parameters", new JSONObject()
                                                        .put("hls_parameters", new JSONObject()
                                                                .put("use_well_known_port", "yes")
                                                                .put("use_ssl", "yes")
                                                                .put("transfer_preset", "")
                                                                .put("segment_duration", 6000)
                                                        )
                                                )
                                        )
                                )
                        )
                        .put("content_uri", "")
                        .put("session_operation_auth", new JSONObject()
                                .put("session_operation_auth_by_signature", new JSONObject()
                                        .put("token", data.getString("token"))
                                        .put("signature", data.getString("signature"))
                                )
                        )
                        .put("content_auth", new JSONObject()
                                .put("auth_type", data.getJSONObject("auth_types").getString("http"))
                                .put("content_key_timeout", data.getInt("content_key_timeout"))
                                .put("service_id", "nicovideo")
                                .put("service_user_id", data.getString("service_user_id"))
                        )
                        .put("client_info", new JSONObject()
                                .put("player_id", data.getString("player_id"))
                        )
                        .put("priority", data.getInt("priority")
                        )
                );
    }

    public static List<URI> getURIListFromM3U8(String m3u8, URI baseurl) {
        List<URI> returnList = new ArrayList<>();
        for(String line: m3u8.split("\n")) {
            if (!(line.startsWith("#") || line.length() == 0)) {
                try {
                    returnList.add(baseurl.resolve(new URI(line)));
                } catch(URISyntaxException ignored){}
            }
        }
        return returnList;
    }

    public static StoppableRunnable getHeartBeatRunnable(HttpClient origClient, JSONObject origData) throws URISyntaxException{
        final URI _uri = new URI("https://api.dmc.nico/api/sessions/" +
                origData.getJSONObject("session").getString("id") +
                "?_format=json&_method=PUT");
        class HeartBeatRunnable implements StoppableRunnable {
            final HttpClient client = origClient;
            final URI uri = _uri;
            JSONObject data = origData;
            HttpRequest heartBeatRequest = null;
            HttpResponse<String> heartBeatResponse = null;
            boolean isFlaggedToStop = false;
            public void run() {
                while(!isFlaggedToStop) {
                    try {
                        Thread.sleep(40000);
                    } catch(InterruptedException ignored){
                        break;
                    }
                    System.out.println("Sending Heartbeat...");
                    heartBeatRequest = HttpRequest.newBuilder()
                            .header("User-Agent", "KKSJNicoPlayer")
                            .uri(uri)
                            .POST(HttpRequest.BodyPublishers.ofString(data.toString()))
                            .build();
                    try {
                        heartBeatResponse = client.send(heartBeatRequest, HttpResponse.BodyHandlers.ofString());
                    } catch(IOException | InterruptedException e){
                        System.out.println("Failed to send HeartBeat.");
                        e.printStackTrace();
                    }
                    data = new JSONObject(heartBeatResponse.body()).getJSONObject("data");
                }
                System.out.println("Stopping Heartbeat...");
            }
            public void stop() {
                isFlaggedToStop = true;
            }
        }
        return new HeartBeatRunnable();
    }

    public static Process getFFmpeg() throws IOException {
        // Exclude video, Set sample rate to 44100, channel count to 2, format to 16-bit low-endian PCM.
        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-i", "-", "-vn", "-ar", "44100", "-ac", "2", "-f", "s16le", "-");
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        return pb.start();
    }

    public static StoppableRunnable getDownloadRunnable(OutputStream origOutputStream, HttpClient origClient, List<URI> origFileList) {
        class DownloadRunnable implements StoppableRunnable {
            final OutputStream ffmpegOutputStream = origOutputStream;
            final HttpClient client = origClient;
            final List<URI> fileList = origFileList;
            short num = 1;
            int chunk;
            HttpRequest fileRequest;
            HttpResponse<InputStream> fileResult;
            InputStream tsInputStream;
            boolean isFlaggedToStop = false;
            public void run() {
                try {
                    for (URI tsUri : fileList) {
                        System.out.print("downloading " + num + "/" + fileList.size() + "\r");
                        fileRequest = HttpRequest.newBuilder()
                                .header("User-Agent", "KKSJNicoPlayer")
                                .uri(tsUri)
                                .build();
                        try {
                            fileResult = client.send(fileRequest, HttpResponse.BodyHandlers.ofInputStream());
                        } catch (InterruptedException | IOException e) {
                            System.out.println("Failed to download " + num + ".");
                            e.printStackTrace();
                            System.exit(0);
                        }
                        tsInputStream = fileResult.body();
                        try {
                            while ((chunk = tsInputStream.read()) != -1) {
                                ffmpegOutputStream.write(chunk);
                            }
                        } catch(IOException e){
                            System.out.println("Failed to read/write streams.");
                        }
                        if(isFlaggedToStop) break; else num++;
                    }
                } finally {
                    System.out.println("Download stopped, closing streams...");
                    try {
                        ffmpegOutputStream.close();
                        tsInputStream.close();
                    } catch(IOException e){
                        System.out.println("Failed to close streams.");
                    }
                }
            }
            public void stop() {
                isFlaggedToStop = false;
            }
        }
        return new DownloadRunnable();
    }

    public static void main(String[] args) throws IOException {
        System.out.println("Get ready, Nicovideo, because I'm here to rip your server off completely...");
        URI uri = null;
        try {
            uri = getNicoURL();
            uri = new URI(args[0]);
        } catch(URISyntaxException e){
            System.out.println("Improper URI detected.");
            e.printStackTrace();
            System.exit(0);
        }
        HttpClient client = HttpClient.newBuilder().build();
        HttpRequest infoRequest = HttpRequest.newBuilder(uri)
                .header("User-Agent", "KKSJNicoPlayer")
                .build();
        HttpResponse<String> infoResponse = null;
        try {
            infoResponse = client.send(infoRequest, HttpResponse.BodyHandlers.ofString());
        } catch(InterruptedException e){
            System.out.println("Failed to send info request.");
            e.printStackTrace();
            System.exit(0);
        }
        if(infoResponse.statusCode() != 200) {
            System.out.println("Failed to connect to Nicovideo server.\nStatusCode: " + infoResponse.statusCode());
            System.exit(0);
        }
        String content = infoResponse.body();
        JSONObject APIData = parseInitialWatchData(content);
        JSONObject DMCPayload = getDMCpayload(APIData);
        URI DMCBaseURI = null;
        try {
            DMCBaseURI = new URI("https://api.dmc.nico/api/sessions?_format=json");
        } catch(URISyntaxException e) {
            e.printStackTrace();
            System.exit(0);
        }
        HttpRequest DMCInitPost = HttpRequest.newBuilder()
                .header("User-Agent", "KKSJNicoPlayer")
                .uri(DMCBaseURI)
                .POST(HttpRequest.BodyPublishers.ofString(DMCPayload.toString()))
                .build();
        HttpResponse<String> DMCResponse = null;
        int statusCode = 0;
        try {
            DMCResponse = client.send(DMCInitPost, HttpResponse.BodyHandlers.ofString());
            statusCode = DMCResponse.statusCode();
            if(statusCode != 201) {
                throw(new Exception());
            }
        } catch(InterruptedException e) {
            System.out.println("Failed to send DMC API request.");
            e.printStackTrace();
            System.exit(0);
        } catch(Exception e) {
            System.out.println("Failed to send DMC API request.\nStatusCode: " + statusCode);
            System.exit(0);
        }
        String heartbeatData = DMCResponse.body();
        JSONObject DMCResponseJSON = new JSONObject(heartbeatData);

        StoppableRunnable heartBeatRunnable = null;
        try {
             heartBeatRunnable = getHeartBeatRunnable(client, DMCResponseJSON.getJSONObject("data"));
        } catch(URISyntaxException e) {
            System.out.println("God is in heaven and your mom iz ded");
            e.printStackTrace();
            System.exit(0);
        }
        Thread heartBeatThread = new Thread(heartBeatRunnable);
        heartBeatThread.start();

        URI masterM3U8URI = null;
        try {
            masterM3U8URI = new URI(DMCResponseJSON.getJSONObject("data").getJSONObject("session").getString("content_uri"));
        } catch(URISyntaxException e) {
            System.out.println("Corrupted content_uri value.");
            e.printStackTrace();
            System.exit(0);
        }

        HttpRequest masterM3U8Request = HttpRequest.newBuilder()
                .header("User-Agent", "KKSJNicoPlayer")
                .uri(masterM3U8URI)
                .build();
        HttpResponse<String> masterM3U8Response = null;
        try {
            masterM3U8Response = client.send(masterM3U8Request, HttpResponse.BodyHandlers.ofString());
        } catch(InterruptedException e) {
            System.out.println("Failed to get master M3U8.");
            e.printStackTrace();
            System.exit(0);
        }
        URI playlistM3U8URI = getURIListFromM3U8(masterM3U8Response.body(), masterM3U8URI).get(0);
        HttpRequest playlistM3U8Request = HttpRequest.newBuilder()
                .header("User-Agent", "KKSJNicoPlayer")
                .uri(playlistM3U8URI)
                .build();
        HttpResponse<String> playlistM3U8Response = null;
        try {
            playlistM3U8Response = client.send(playlistM3U8Request, HttpResponse.BodyHandlers.ofString());
        } catch(InterruptedException e){
            System.out.println("Failed to get master M3U8.");
            e.printStackTrace();
            System.exit(0);
        }
        List<URI> fileList = getURIListFromM3U8(playlistM3U8Response.body(), playlistM3U8URI);

        Process ffmpeg = getFFmpeg();

        StoppableRunnable downloadRunnable = getDownloadRunnable(ffmpeg.getOutputStream(), client, fileList);
        Thread downloadThread = new Thread(downloadRunnable);
        downloadThread.start();

        AudioFormat format = new AudioFormat(44100, 16, 2, true, false);
        SourceDataLine line = null;
        InputStream ffmpegInputStream = ffmpeg.getInputStream();
        try {
            line = AudioSystem.getSourceDataLine(format);
            line.open(format);
        } catch(LineUnavailableException e){
            System.out.println("Line is unavailable.");
            e.printStackTrace();
            System.exit(0);
        }
        int bytesRead;
        int bytesToRead = 44100;
        byte[] chunks = new byte[bytesToRead];

        line.start();
        while(true){
            bytesRead = ffmpegInputStream.read(chunks, 0, bytesToRead);
            if(bytesRead == -1) break;
            else if(bytesRead > 0) line.write(chunks, 0, bytesRead);
        }

        try {
            downloadThread.join();
        } catch(InterruptedException e){
            System.out.println("downloadThread interrupted");
            e.printStackTrace();
            System.exit(0);
        }

        heartBeatRunnable.stop();
        downloadRunnable.stop();

        System.out.println();

        System.exit(0);
    }
}