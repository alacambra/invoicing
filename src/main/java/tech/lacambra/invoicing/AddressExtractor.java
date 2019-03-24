package tech.lacambra.invoicing;

import com.itextpdf.text.Document;
import com.itextpdf.text.DocumentException;
import com.itextpdf.text.pdf.PdfWriter;
import com.itextpdf.tool.xml.XMLWorkerHelper;
import com.itextpdf.tool.xml.exceptions.RuntimeWorkerException;

import javax.json.*;
import javax.json.stream.JsonCollectors;
import javax.mail.*;
import javax.mail.search.AndTerm;
import javax.mail.search.ComparisonTerm;
import javax.mail.search.ReceivedDateTerm;
import javax.mail.search.SearchTerm;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;


public class AddressExtractor {

  private static final Logger LOGGER = Logger.getLogger(AddressExtractor.class.getName());
  private static final String TEXT_PLAIN = "text/plain";
  private static final String TEXT_HTML = "text/html";
  private JsonObject config;

  public static void main(String[] args) throws IOException {

    AddressExtractor extractor = new AddressExtractor();
    extractor.run();
//    extractor.checkTargetOrCreate();
  }

  private JsonObject loadConfig() {

    if (config != null) {
      return config;
    }

    String location = System.getenv("EMAIL_PROPS_FILE") != null ?
        System.getenv("EMAIL_PROPS_FILE") :
        System.getProperty("email.props.file.properties");

    if (location == null) {
      throw new RuntimeException("properties not found");
    }

    LOGGER.info("[loadConfig] Using location " + location);

    try {
      config = Json.createReader(new FileInputStream(location)).readObject();
      return config;
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private Properties loadProperties(JsonObject config) {
    Properties properties = new Properties();
    properties.put("host", config.getString("host"));
    properties.put("username", config.getString("username"));
    properties.put("password", config.getString("password"));
    properties.put("protocol", config.getString("protocol"));
    properties.setProperty("mail.imap.ssl.enable", "true");

    return properties;
  }

  private Store loadEmailStore() {
    Properties props = loadProperties(loadConfig());

    String host = props.getProperty("host");
    String username = props.getProperty("username");
    String password = props.getProperty("password");
    String protocol = props.getProperty("protocol");
    int port = Integer.parseInt(props.getProperty("protocol"));

    try {
      //Connect to the server
      Session session = Session.getDefaultInstance(props, null);
      Store store = session.getStore(protocol);
      store.connect(host, 993, username, password);
      Stream.of(store.getPersonalNamespaces()).forEach(System.out::println);

      return store;
    } catch (MessagingException e) {
      throw new RuntimeException(e);
    }

  }

  void run() {
    try {
      Store store = loadEmailStore();
      //open the inbox folder
      Folder inbox = store.getFolder(config.getString("imapFolder", "INBOX"));

      inbox.open(Folder.READ_ONLY);
      List<Message> messages = Arrays.asList(
          inbox.search(betweenDatesTerm(
              LocalDateTime.now().minusDays(config.getInt("deltaStart", 1)).with(LocalTime.MIN),
              LocalDateTime.now().minusDays(config.getInt("deltaEnd", 1)).with(LocalTime.MAX)
              )
          ));

      AtomicInteger i = new AtomicInteger(0);
      messages.stream()
          .filter(this::isInvoiceEmail)
          .peek(message -> LOGGER.info("[run] found relevant message. " + i.getAndIncrement() + " "))//  + this.msgToJsonObject(message)))
          .forEach(message -> {

            String address = null;
            try {
              LOGGER.info("[run] Begin processing of:" + message.getSubject());

              Path folder = checkTargetOrCreate(message.getReceivedDate());
              if (!Files.exists(folder)) {
                Files.createDirectory(folder);
              }

              LOGGER.info("[run] Saving attachments: " + message.getSubject());
              JsonArray fileNames = saveAttachmemnt(message, folder);

              LOGGER.info("[run] Saving message: " + message.getSubject());
              saveMessage(message, folder, fileNames, folder);

              LOGGER.info("[run] Message processed: " + message.getSubject());

            } catch (Exception e) {
              LOGGER.log(Level.WARNING, "[run] Error" + e.getMessage(), e);

            }
          });
//
//          .peek(this::saveAttachmemnt)
//          .map(this::msgToJsonObject)
//          .forEach(System.out::println);

      inbox.close(false);
      store.close();

    } catch (NoSuchProviderException nspe) {
      System.err.println("invalid provider name");
    } catch (MessagingException me) {
      System.err.println("messaging exception");
      me.printStackTrace();
    }
  }


  private String createMessageName(Message message) throws MessagingException {
    return message.getSubject().replaceAll("[/\\ ]", "_").replace(" ", "_").toLowerCase();
  }

  private void saveMessage(Message message, Path folder, JsonArray fileNames, Path path) {

    JsonObject msg = msgToJsonObject(message, fileNames, path);
    String name = msg.getString("subject").replaceAll("[/\\ ]", "_").replace(" ", "_").toLowerCase();
    folder = folder.resolve(name + ".json");
    byte[] bytes = msg.toString().getBytes();
    try {
      Files.write(folder, bytes);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

  }

  private SearchTerm betweenDatesTerm(LocalDateTime start, LocalDateTime end) {

    SearchTerm newerThan = new ReceivedDateTerm(ComparisonTerm.GE, Date.from(start.toInstant(ZoneOffset.of("+1"))));
    SearchTerm olderThan = new ReceivedDateTerm(ComparisonTerm.LE, Date.from(end.toInstant(ZoneOffset.of("+1"))));
    return new AndTerm(olderThan, newerThan);


  }

  public JsonArray saveAttachmemnt(Message message, Path folder) {
    Multipart multipart = null;
    JsonArrayBuilder builder = Json.createArrayBuilder();
    try {

      if (!message.getContentType().toLowerCase().contains("multipart")) {
        return builder.build();
      }

      multipart = (Multipart) message.getContent();

      for (int i = 0; i < multipart.getCount(); i++) {

        BodyPart bodyPart = multipart.getBodyPart(i);

        if (!Part.ATTACHMENT.equalsIgnoreCase(bodyPart.getDisposition()) &&
            (bodyPart.getFileName() == null || bodyPart.getFileName().isEmpty())) {
          continue; // dealing with attachments only
        }

        try (InputStream is = bodyPart.getInputStream()) {

          String fName = createMessageName(message) + "--" + bodyPart.getFileName().replace(".", "--" + System.currentTimeMillis() + ".");
          builder.add(fName);
          Path target = folder.resolve(fName);
          Files.copy(is, target);

        }
      }
    } catch (IOException | MessagingException e) {
      LOGGER.info("[saveAttachmemnt] More likely Not a multipart message: " + e.getClass().getSimpleName() + ":" + e.getMessage());
    }

    return builder.build();
  }

  public JsonArray getContent(Message message, Path folder) {
    Multipart multipart;
    JsonArrayBuilder text = Json.createArrayBuilder();
    try {

      String type = message.getContentType().toLowerCase();
      boolean isHtml = Stream.of("html").anyMatch(type::contains);
      boolean isPlain = Stream.of("text").anyMatch(type::contains);

      String body = "";

      if (isHtml) {

        body = (String) message.getContent();
        generatePDFFromHTML(createMessageName(message), body, folder);

      } else if (isPlain) {

        body = (String) message.getContent();
        saveEmailAsTextFile(createMessageName(message), body, folder, "txt");

      } else {

        multipart = (Multipart) message.getContent();
        for (int i = 0; i < multipart.getCount(); i++) {
          BodyPart bodyPart = multipart.getBodyPart(i);
          if (!Part.ATTACHMENT.equalsIgnoreCase(bodyPart.getDisposition())) {
            try (BufferedReader buff = new BufferedReader(new InputStreamReader(bodyPart.getInputStream()))) {
              String bodyPartText = buff.lines().collect(Collectors.joining("\n"));
              if (bodyPart.getContentType().contains(TEXT_HTML)) {
                try {
                  generatePDFFromHTML(createMessageName(message), bodyPartText, folder);
                } catch (RuntimeWorkerException e) {
                  saveEmailAsTextFile(createMessageName(message), bodyPartText, folder, "html");
                }
              } else if (bodyPart.getContentType().contains(TEXT_PLAIN)) {
                saveEmailAsTextFile(createMessageName(message), bodyPartText, folder, "txt");
              }
            }
          }
        }

        saveEmailAsTextFile(createMessageName(message), body, folder, "txt");
      }

      if (!body.isEmpty()) {
        text.add(body.replaceAll("(?s)<[^>]*>(\\s*<[^>]*>)*", " "));
        text.add(body);
      }

    } catch (IOException | MessagingException e) {
      throw new RuntimeException(e);
    }

    return text.build();
  }

  private Path checkTargetOrCreate(Date date) {

    String path = config.getString("output");
    Path target = Paths.get(path);
    target = target.resolve(Paths.get(getPeriodName(date)));

    if (!Files.exists(target)) {
      try {
        Files.createDirectory(target);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }

    return target;
  }

  String getPeriodName(Date date) {
    return LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MMMM_yyyy")).toUpperCase();
  }

  private JsonObject msgToJsonObject(Message message, JsonArray fileNames, Path path) {

    if (fileNames == null) {
      fileNames = JsonArray.EMPTY_JSON_ARRAY;
    }

    try {
      return Json.createObjectBuilder()
          .add("from", recipientsToString(message))
          .add("to", Stream.of(message.getFrom()).map(this::addressToStr).map(Object::toString).collect(Collectors.joining(", ")))
          .add("subject", message.getSubject())
          .add("messageNumber", message.getMessageNumber())
          .add("body", getContent(message, path))
          .add("received", message.getSentDate().toString())
          .add("files", fileNames)
          .build();

    } catch (MessagingException e) {
      throw new RuntimeException(e);
    }
  }

  private boolean hasRelevantSubject(Message message) {

    JsonArray relevantSubjects = loadConfig().getJsonArray("relevantSubjects");
    LOGGER.fine("[hasRelevantSubject] Relevant subjects: " + relevantSubjects);

    return relevantSubjects.stream().anyMatch(subject -> {
      try {
        return message.getSubject().toLowerCase().contains(((JsonString) subject).getString().toLowerCase())
            ||
            ((JsonString) subject).getString().toLowerCase().contains(message.getSubject().toLowerCase());
      } catch (MessagingException e) {
        throw new RuntimeException(e);
      }
    });
//    return true;
  }

  private boolean isInvoiceEmail(Message message) {

    String filterEmail = loadConfig().getString("filterEmail");
    try {
      LOGGER.info("[isInvoiceEmail] Filtering email " + filterEmail + ", from " + getFrom(message));
      return List.of(message.getAllRecipients()).toString().contains(filterEmail);
    } catch (MessagingException e) {
      throw new RuntimeException(e);
    }
  }

  private JsonArray recipientsToString(Message message) {


    try {
      return Stream.of(message.getAllRecipients())
          .map(this::addressToStr)
//          .filter(jsonObject -> jsonObject.getString("txt").contains("invoice@lacambra.tech"))
          .collect(JsonCollectors.toJsonArray());

    } catch (MessagingException e) {
      throw new RuntimeException(e);
    }


  }


  JsonObject addressToStr(Address address) {
    return Json.createObjectBuilder().add("type", address.getType()).add("txt", address.toString()).build();
  }

  private String getFrom(Message msg) throws MessagingException {
    String from = "";
    Address a[] = msg.getAllRecipients();
    if (a == null) return null;
    for (int i = 0; i < a.length; i++) {
      Address address = a[i];
      from = from + address.toString();
    }

    return from;
  }

  private String removeQuotes(String stringToModify) {
    int indexOfFind = stringToModify.indexOf(stringToModify);
    if (indexOfFind < 0) return stringToModify;

    StringBuffer oldStringBuffer = new StringBuffer(stringToModify);
    StringBuffer newStringBuffer = new StringBuffer();
    for (int i = 0, length = oldStringBuffer.length(); i < length; i++) {
      char c = oldStringBuffer.charAt(i);
      if (c == '"' || c == '\'') {
        // do nothing
      } else {
        newStringBuffer.append(c);
      }

    }
    return new String(newStringBuffer);
  }

  private void generatePDFFromHTML(String fileName, String body, Path folder) {

    if (body == null) {
      body = "";
    }


    Path filePath = folder.resolve(fileName + "-" + System.currentTimeMillis() + ".email.pdf");

    try (InputStream bodyStream = new ByteArrayInputStream(body.getBytes())) {

      Document document = new Document();
      PdfWriter writer = PdfWriter.getInstance(document, new FileOutputStream(filePath.toString()));
      document.open();
      XMLWorkerHelper.getInstance().parseXHtml(writer, document, bodyStream);
      document.close();

    } catch (DocumentException | RuntimeWorkerException | IOException e) {

      if (Files.exists(filePath)) {
        try {
          Files.delete(filePath);
        } catch (IOException e1) {
          throw new RuntimeException(e);
        }
      }

      if (e instanceof RuntimeWorkerException) {
        throw (RuntimeWorkerException) e;
      }

      throw new RuntimeException(e);
    }
  }

  private void saveEmailAsTextFile(String fileName, String body, Path folder, String extension) {

    if (body == null) {
      body = "";
    }

    try {
      Files.write(folder.resolve(fileName + "-" + System.currentTimeMillis() + ".email." + extension), body.getBytes());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }


}
