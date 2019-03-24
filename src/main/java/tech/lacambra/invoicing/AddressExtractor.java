package tech.lacambra.invoicing;

import org.w3c.tidy.Tidy;
import org.xhtmlrenderer.pdf.ITextRenderer;

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
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;


public class AddressExtractor {

  private static final Logger LOGGER = Logger.getLogger(AddressExtractor.class.getName());
  private static final String TEXT_PLAIN = "text/plain";
  private static final String TEXT_HTML = "text/html";
  private static final String MULTIPART_ALTERNATIVE = "multipart/alternative";
  private static final String MULTIPART_MIXED = "multipart/mixed";
  private static final String APPLICATION_PDF = "application/pdf";
  private JsonObject config;

  public static void main(String[] args) {

    AddressExtractor extractor = new AddressExtractor();
    extractor.run();

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

            try {
              LOGGER.info("[run] Begin processing for mail:" + message.getSubject());

              Path folder = checkTargetFolderOrCreate(message.getReceivedDate());
              if (!Files.exists(folder)) {
                Files.createDirectory(folder);
              }

              LOGGER.info("[run] Saving attachments for mail: " + message.getSubject());
              JsonArray attachmentNames = saveAttachment(message, folder);

              LOGGER.info("[run] Saving message for mail: " + message.getSubject());
              saveMessage(message, folder, attachmentNames, folder);

              LOGGER.info("[run] Message processed: " + message.getSubject());

            } catch (Exception e) {
              LOGGER.log(Level.WARNING, "[run] Error" + e.getMessage(), e);

            }
          });

      inbox.close(false);
      store.close();

    } catch (NoSuchProviderException nspe) {
      System.err.println("invalid provider name");
    } catch (MessagingException me) {
      System.err.println("messaging exception");
      me.printStackTrace();
    }
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
    properties.setProperty("port", config.getString("port"));
    properties.setProperty("mail.imap.ssl.enable", "true");

    return properties;
  }

  private Store loadEmailStore() {
    Properties props = loadProperties(loadConfig());

    String host = props.getProperty("host");
    String username = props.getProperty("username");
    String password = props.getProperty("password");
    String protocol = props.getProperty("protocol");
    String port = props.getProperty("port");

    try {
      Session session = Session.getDefaultInstance(props, null);
      Store store = session.getStore(protocol);
      store.connect(host, Integer.parseInt(port), username, password);
      Stream.of(store.getPersonalNamespaces()).forEach(System.out::println);

      return store;
    } catch (MessagingException e) {
      throw new RuntimeException(e);
    }
  }


  private String createMessageName(Message message) throws MessagingException {
    return message.getSubject().replaceAll("[/\\ ]", "_").replace(" ", "_").toLowerCase();
  }

  private void saveMessage(Message message, Path folder, JsonArray attachmentNames, Path path) {

    try {

      String from = Stream.of(message.getFrom()).map(this::addressToStr).map(Object::toString).collect(Collectors.joining(", "));
      String subject = message.getSubject();
      String receivedOn = message.getReceivedDate().toString();
      String to = Stream.of(message.getFrom()).map(this::addressToStr).map(Object::toString).collect(Collectors.joining(", "));

      int messageNumber = message.getMessageNumber();
      JsonArray content = getContent(message, path);

      JsonObject jsonMsg = msgToJsonObject(from, to, subject, messageNumber, content, receivedOn, attachmentNames);
      String name = jsonMsg.getString("subject").replaceAll("[/\\ ]", "_").replace(" ", "_").toLowerCase();
      folder = folder.resolve(name + ".json");

      byte[] bytes = jsonMsg.toString().getBytes();
      Files.write(folder, bytes);

    } catch (MessagingException | IOException e) {
      e.printStackTrace();
    }
  }

  private SearchTerm betweenDatesTerm(LocalDateTime start, LocalDateTime end) {

    SearchTerm newerThan = new ReceivedDateTerm(ComparisonTerm.GE, Date.from(start.toInstant(ZoneOffset.of("+1"))));
    SearchTerm olderThan = new ReceivedDateTerm(ComparisonTerm.LE, Date.from(end.toInstant(ZoneOffset.of("+1"))));

    return new AndTerm(olderThan, newerThan);
  }

  public JsonArray saveAttachment(Message message, Path folder) {

    try {

      if (!isMultipart(message)) {
        return JsonValue.EMPTY_JSON_ARRAY;
      }

      Multipart multipart = (Multipart) message.getContent();

      return saveAttachmentFromAllParts(multipart, folder, createMessageName(message));

    } catch (MessagingException | IOException e) {
      LOGGER.info("[saveAttachment] More likely not a multipart message: " + e.getClass().getSimpleName() + ":" + e.getMessage());
    }

    return JsonValue.EMPTY_JSON_ARRAY;
  }

  private JsonArray saveAttachmentFromAllParts(Multipart multipart, Path folder, String messageName) {
    JsonArrayBuilder builder = Json.createArrayBuilder();

    try {
      for (int i = 0; i < multipart.getCount(); i++) {

        BodyPart bodyPart = multipart.getBodyPart(i);

        if (!partIsAttachment(bodyPart)) {
          continue;
        }

        savePart(bodyPart, messageName, folder).ifPresent(builder::add);
      }
    } catch (MessagingException e) {
      LOGGER.info("[saveAttachmentFromAllParts] Error reading message: " + e.getClass().getSimpleName() + ":" + e.getMessage());

    }

    return builder.build();
  }

  private Optional<String> savePart(BodyPart bodyPart, String messageName, Path folder) {

    try (InputStream is = bodyPart.getInputStream()) {

      String fName = messageName + "--" + bodyPart.getFileName().replace(".", "--" + System.currentTimeMillis() + ".");
      Path target = folder.resolve(fName);
      Files.copy(is, target);

      return Optional.of(fName);

    } catch (IOException | MessagingException e) {
      LOGGER.info("[savePart] Error reading message: " + e.getClass().getSimpleName() + ":" + e.getMessage());
    }

    return Optional.empty();
  }

  private boolean partIsAttachment(BodyPart bodyPart) {
    try {
      return Part.ATTACHMENT.equalsIgnoreCase(bodyPart.getDisposition()) &&
          (bodyPart.getFileName() == null || bodyPart.getFileName().isEmpty());
    } catch (MessagingException e) {
      LOGGER.info("[partIsAttachment] Error reading message part: " + e.getClass().getSimpleName() + ":" + e.getMessage());
      return false;
    }
  }

  private boolean isMultipart(Message message) throws MessagingException {
    return message.getContentType().toLowerCase().contains("multipart");
  }

  public JsonArray getContent(Message message, Path folder) {

    try {
      String msgName = createMessageName(message);
      String type = message.getContentType().toLowerCase();

      return getAndSaveContent(type, message.getContent(), msgName, folder, Json.createArrayBuilder()).build();

    } catch (IOException | MessagingException e) {
      throw new RuntimeException(e);
    }

  }

  private boolean isTextHtml(String type) {
    return type.contains(TEXT_HTML);
  }

  private boolean isTextPlain(String type) {
    return type.contains(TEXT_PLAIN);
  }

  private boolean isMultipartAlternative(String type) {
    return type.contains(MULTIPART_ALTERNATIVE);
  }

  private boolean isMultipartMixed(String type) {
    return type.contains(MULTIPART_MIXED);
  }

  private boolean isMultipart(String type) {
    return isMultipartAlternative(type) || isMultipartMixed(type);
  }

  private boolean isApplicationPdf(String type) {
    return type.contains(APPLICATION_PDF);
  }

  public JsonArrayBuilder getAndSaveContent(String partType, Object content, String msgName, Path folder, JsonArrayBuilder builder) {

    try {

      String type = partType.toLowerCase();
      LOGGER.info(String.format("[getAndSaveContent] Message %s is of type %s", msgName, type));
      String body = "";
      String encoding = getEncoding(type);

      LOGGER.info("[getAndSaveContent] Using encoding " + encoding);


      if (isTextHtml(type)) {

        body = getContentString(content);
        generatePDFFromHTML(msgName, body, folder, encoding);
        saveEmailAsTextFile(msgName, body, folder, "html", encoding);

      } else if (isTextPlain(type)) {

        body = getContentString(content);
        saveEmailAsTextFile(msgName, body, folder, "txt", encoding);

      } else if (isApplicationPdf(type) || (content instanceof BodyPart && partIsAttachment((BodyPart) content))) {
        savePart((BodyPart) content, msgName, folder);

      } else if (isMultipart(type)) {

        Multipart multipart = getContentMultipart(content);

        for (int i = 0; i < multipart.getCount(); i++) {
          BodyPart bodyPart = multipart.getBodyPart(i);
          builder = getAndSaveContent(bodyPart.getContentType(), bodyPart, msgName, folder, builder);
        }

      } else {
        LOGGER.warning("[getAndSaveContent] Unexpected content type ");
      }

      if (!body.isEmpty()) {
        builder.add(body.replaceAll("(?s)<[^>]*>(\\s*<[^>]*>)*", " "));
        builder.add(body);
      }

    } catch (MessagingException | IOException e) {
      throw new RuntimeException(e);
    }

    return builder;
  }

  private String getContentString(Object content) throws IOException, MessagingException {
    if (content instanceof BodyPart) {
      return (String) ((BodyPart) content).getContent();
    } else {
      return (String) content;
    }
  }


  private String getEncoding(String type) {

    String[] enc = type.split("charset=");
    if (enc.length == 2) {
      return enc[1];
    }
    return "utf-8";

  }

  private Multipart getContentMultipart(Object content) throws IOException, MessagingException {
    if (content instanceof BodyPart) {
      return (Multipart) ((BodyPart) content).getContent();
    } else {
      return (Multipart) content;
    }
  }

  private Path checkTargetFolderOrCreate(Date date) {

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

  private JsonObject msgToJsonObject(String from, String to, String subject, int messageNumber, JsonArray content, String receivedOn, JsonArray fileNames) {

    if (fileNames == null) {
      fileNames = JsonArray.EMPTY_JSON_ARRAY;
    }

    return Json.createObjectBuilder()
        .add("from", from)
        .add("to", to)
        .add("subject", subject)
        .add("messageNumber", messageNumber)
        .add("body", content)
        .add("received", receivedOn)
        .add("files", fileNames)
        .build();
  }

  private boolean isInvoiceEmail(Message message) {

    String filterEmail = loadConfig().getString("filterEmail");
    try {
      if (getRecipients(message).contains(filterEmail)) {
        LOGGER.info("[isInvoiceEmail] found invoicing email for filtered email: " + filterEmail + ". All recipients: " + getRecipients(message) + ", from " + getFrom(message));
        return true;
      }
    } catch (MessagingException e) {
      LOGGER.log(Level.WARNING, "[isInvoiceEmail] Error: " + e.getCause(), e);
      return false;
    }

    return false;
  }


  JsonObject addressToStr(Address address) {
    return Json.createObjectBuilder().add("type", address.getType()).add("txt", address.toString()).build();
  }

  private String getFrom(Message msg) throws MessagingException {
    Address[] a = msg.getFrom();
    return addressesToString(a);
  }

  private String getRecipients(Message msg) throws MessagingException {
    Address[] a = msg.getAllRecipients();
    return addressesToString(a);
  }

  private String addressesToString(Address[] addresses) throws MessagingException {
    StringBuilder addr = new StringBuilder();
    if (addresses == null) return null;
    for (int i = 0; i < addresses.length; i++) {
      Address address = addresses[i];
      addr.append(address.toString());
    }

    return addr.toString();
  }

  private void generatePDFFromHTML(String fileName, String body, Path folder, String encoding) {

    if (body == null) {
      body = "";
    }


    Path filePath = folder.resolve(fileName + "-" + System.currentTimeMillis() + ".email.pdf");

    try (InputStream bodyStream = new ByteArrayInputStream(body.getBytes(encoding))) {

      Tidy tidy = new Tidy();
      tidy.setQuiet(false);
      tidy.setBreakBeforeBR(true);
      tidy.setXHTML(true);
      tidy.setShowWarnings(false);
      tidy.setMakeClean(true);
      String tmpFileName = filePath.toString() + ".xhtml";
      FileOutputStream fileOutputStream = new FileOutputStream(tmpFileName);
      tidy.parseDOM(bodyStream, fileOutputStream);
      fileOutputStream.close();

      if (Files.size(Paths.get(tmpFileName)) == 0) {
        Files.delete(Paths.get(tmpFileName));
        return;
      }

      File f = Paths.get(tmpFileName).toFile();
      ITextRenderer renderer = new ITextRenderer();
      renderer.setDocument(f);
      renderer.layout();

      try (OutputStream os = new FileOutputStream(filePath.toString())) {
        renderer.createPDF(os);
      }

      Files.delete(Paths.get(tmpFileName));

    } catch (IOException | com.lowagie.text.DocumentException e) {

      if (Files.exists(filePath)) {
        try {
          Files.delete(filePath);
        } catch (IOException e1) {
          throw new RuntimeException(e);
        }
      }

      throw new RuntimeException(e);
    }
  }

  private void saveEmailAsTextFile(String fileName, String body, Path folder, String extension, String encoding) {

    if (body == null) {
      body = "";
    }

    try {
      Files.write(folder.resolve(fileName + "-" + System.currentTimeMillis() + ".email." + extension), body.getBytes(encoding));
    } catch (IOException e) {
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
  }
}
