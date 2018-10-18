import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Serializable;
import java.io.StringReader;
import static java.lang.Math.abs;
import static java.lang.Math.floor;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javafx.util.Pair;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/**
 *
 * @author Texhnolyze
 */
public class DBCreating {
    
    static final String jdbc_url = "jdbc:mysql://localhost:3306/world";
    static final String user = "root", password = "admin";
    
    static Connection jdbc_connection;
    
    static {
        try {
            jdbc_connection = DriverManager.getConnection(jdbc_url, user, password);
        } catch (SQLException ex) {
            Logger.getLogger(DBCreating.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
    
    public static void main(String[] args) throws SQLException, IOException {
       try {
           collect_all_quests();
//            collect_quests_require_edits();
           apply_edits();
           build_positive_exclusive_groups();
           collect_quest_conditions();
           parse_isengard_db();
           // 3, 4, 5 are id's of same event (Darkmoon Faire)
           ru_event_name.remove(4);
           ru_event_name.remove(5);
           List<Integer> l = Arrays.asList(4, 5);
           for (QuestStarter qs : quest_starters) {
               if (qs.events_id.contains(4) || qs.events_id.contains(5)) {
                   qs.events_id.removeAll(l);
                   if (!qs.events_id.contains(3))
                       qs.events_id.add(3);
               }
           }
           for (Quest q : quests.values()) {
               if (q.events_id.contains(4) || q.events_id.contains(5)) {
                   q.events_id.removeAll(l);
                   if (!q.events_id.contains(3))
                       q.events_id.add(3);
               }
           }
           process_quest_starters();
           all_to_lua_table();
       } catch (Exception e) {
           logger.flush();
           logger.close();
           throw e;
       }
    }
    
    static void collect_all_quests() throws SQLException, IOException {
        Statement s = jdbc_connection.createStatement(); 
        ResultSet rs = s.executeQuery("SELECT id FROM quest_template;");
        while (rs.next()) {
            int id = rs.getInt(1);
            if (id == 9679) // 504 error, don't know why
                continue;
            Document doc = get_document(quest_type, id);
            List<Integer> prev = get_prev_quests_in_chain(doc);
            List<Integer> req = get_quests(doc, required_quests);
            List<Integer> req_one_of = get_quests(doc, required_one_of_quests);
            List<Integer> active = get_quests(doc, quests_must_be_active);
            Quest q = new Quest(id);
            int i = collect_base_quest_props(q, id, doc);
            if (i == -1) // no such quest
                continue;
            quests.put(id, q);
            q.prev_quest_in_chain = prev.isEmpty() ? 0 : prev.get(0);
            q.quest_must_be_active = active.isEmpty() ? 0 : active.get(0);
            q.req_quests = req;
            q.req_one_of_quests = req_one_of;
        }
    }
    
    static int collect_base_quest_props(Quest q, int id, Document doc) throws IOException, SQLException {
        if (doc == null)
            doc = get_document(quest_type, id);
        Statement s = jdbc_connection.createStatement(); 
        ResultSet rs = s.executeQuery("SELECT Flags FROM quest_template WHERE ID = " + id + ";");
        q.allowable_races = get_allowable_races(doc);
        if (q.allowable_races == -1) // no such quest
            return -1;
        q.allowable_classes = get_allowable_classes(doc);
        q.ru_title = get_name(doc);
        q.events_id = get_related_events(doc);
        int[] min_max = get_min_max_lvl(doc);
        q.min_level = min_max[0];
        q.max_level = min_max[1];
        int[] req_skill = get_required_skill(id, doc);
        q.req_skill_id = req_skill[0];
        q.req_skill_points = req_skill[1];
        q.zone_or_sort = get_zone_or_sort(id, doc);
        q.flags = get_flags(id, doc);
        rs.next();
        if ((rs.getInt(1) & 1024) != 0)
            q.flags |= 1024;
        q.special_flags = get_special_flags(doc);
        q.valid = is_valid_quest(doc);
        return 1;
    }
    
    
    static void collect_quests_require_edits() throws IOException {
        Set<Integer> quests_require_edits = new HashSet<>();
        for (Quest q : quests.values()) {
            if (require_edits(q))
                quests_require_edits.add(q.id);
        }
        PrintWriter pw = new PrintWriter(new File("quests_require_edits.txt"));
        pw.append("ID|PREV_QUEST_IN_CHAIN|QUEST_MUST_BE_ACTIVE|[REQUIRED_QUESTS]|[REQUIRED_ONE_OF_QUESTS]|[QUEST_STARTER_TYPE_ID]\n");
        quests_require_edits.stream().sorted().forEach(quest -> pw.append(quest + "").append("\n"));
        pw.flush();
        pw.close();
    }
    
    static boolean require_edits(Quest q) throws IOException {
        if (!q.req_one_of_quests.isEmpty())
            return true;
        if (q.prev_quest_in_chain != 0 && !q.req_quests.isEmpty())
            return true;
        Document doc = get_document(quest_type, q.id);
        String s = doc.getElementById("infobox-contents0").nextElementSibling().html();
        int start = s.indexOf("Начало: ");
        int end = s.indexOf("Конец: ");
        if (start == -1 && end != -1)
            return true;
        if (!q.valid && start != -1)
            return true;
        if (q.valid && start == -1)
            return true;
        boolean b = q.valid && ((q.prev_quest_in_chain != 0 && !quests.get(q.prev_quest_in_chain).valid) ||
                    q.req_quests.stream().anyMatch(id -> !quests.get(id).valid) ||
                    (!q.req_one_of_quests.isEmpty() && q.req_one_of_quests.stream().allMatch(id -> !quests.get(id).valid)) ||
                    (q.quest_must_be_active != 0 && !quests.get(q.quest_must_be_active).valid));
        return b;
    }
    
    static File edits = new File("edits.txt"); //in this file edits about the structure of quest chains
   
    static void apply_edits() throws IOException, SQLException {
        Scanner s = new Scanner(edits);
        s.nextLine();
        String line;
        while (s.hasNextLine()) {
            line = s.nextLine();
            String[] split = line.split("\\|");
            if (split.length < 6) {
                if (split.length != 1)
                    throw new RuntimeException(split[0]);
                continue;
            }
            int id = Integer.parseInt(split[0]);
            Quest q = quests.get(id);
            q.prev_quest_in_chain = Integer.parseInt(split[1]);
            q.quest_must_be_active = Integer.parseInt(split[2]);
            String[] req_quests = split[3].split(" ");
            if (Integer.parseInt(req_quests[0]) == 0)
                q.req_quests = Collections.EMPTY_LIST;
            else {
                q.req_quests = new ArrayList<>();
                for (String str : req_quests)
                    q.req_quests.add(Integer.parseInt(str));
            }
            String[] req_one_of_quests = split[4].split(" ");
            if (Integer.parseInt(req_one_of_quests[0]) == 0)
                q.req_one_of_quests = Collections.EMPTY_LIST;
            else {
                q.req_one_of_quests = new ArrayList<>();
                for (String str : req_one_of_quests)
                    q.req_one_of_quests.add(Integer.parseInt(str));
            }
            String[] qs_type_id = split[5].split(" ");
            if (qs_type_id.length == 2) {
                boolean npc = qs_type_id[0].equals("1");
                int qs_id = Integer.parseInt(qs_type_id[1]);
                for (QuestStarter qs : quest_starters) {
                    if (qs.id == qs_id && qs.npc == npc) {
                        qs.quests_started.add(id);
                        return;
                    }
                }
                System.out.println("ERROR");
            }
            if (split.length == 7)  // valid label
                q.valid = true;
        }
    }
    
    static final Map<Integer, List<Integer>> positive_eg = new HashMap<>();
    
    static void build_positive_exclusive_groups() throws SQLException, IOException {
        Set<Integer> eg_found = new HashSet<>();
        for (Quest q : quests.values()) {
            int id = q.id;
            if (eg_found.contains(id))
                continue;
            List<Integer> l = get_quests(get_document(quest_type, id), quests_finished_by_this_quest);
            if (!l.isEmpty()) {
                l.add(id);
                eg_found.addAll(l);
                for (int q_id : l)
                    quests.get(q_id).positive_exclusive_group = id;
                positive_eg.put(id, l);
            }
        }
    }

    static void collect_quest_conditions() throws SQLException, IOException {
        for (Quest q : quests.values()) {
            QuestConditions qc = get_conditions(q.id, get_document(quest_type, q.id));
            if (qc != null)
                conditions.put(q.id, qc);
        }
    }
    
    static final Pattern race_pattern = Pattern.compile("race=[0-9]+");
    
    static int get_allowable_races(Document doc) {
        Element e = doc.getElementById("infobox-contents0");
        if (e == null)
            return -1;
        String s = e.nextElementSibling().html();
        if (s.contains("Расы: Обе")) 
            return 0;
        else if (s.contains("Расы: Орда")) 
            return 690;
        else if (s.contains("Расы: Альянс")) 
            return 1101;
        else {
            int races = 0;
            Matcher m = race_pattern.matcher(s);
            while (m.find()) 
                races |= (1 << (Integer.parseInt(s.substring(m.start() + "race=".length(), m.end())) - 1));
            return races;
        }
    }
    
    static final Pattern class_pattern = Pattern.compile("class=[0-9]+");
    
    static int get_allowable_classes(Document doc) {
        String s = doc.getElementById("infobox-contents0").nextElementSibling().html();
        if (!s.contains("Класс:") && !s.contains("Классы:")) 
            return 0;
        else {
            int classes = 0;
            Matcher m = class_pattern.matcher(s);
            while (m.find()) 
                classes |= (1 << (Integer.parseInt(s.substring(m.start() + "class=".length(), m.end())) - 1));
            return classes;
        }
    }
    
    static final Pattern lvl_pattern = Pattern.compile("Требуется уровень: ([0-9]+)(\\s-\\s[0-9]+)*");

    static int[] get_min_max_lvl(Document doc) {
        String s = doc.getElementById("infobox-contents0").nextElementSibling().html();
        Matcher m = lvl_pattern.matcher(s);
        if (!m.find()) 
            return new int[] {0, 0};
        else {
            String[] split = s.substring(m.start() + "Требуется уровень: ".length(), m.end()).split("-");
            int min = Integer.parseInt(split[0].trim());
            int max = split.length == 2 ? Integer.parseInt(split[1].trim()) : 0;
            return new int[] {min, max};
        }
    }
    
    static int get_zone_or_sort(int quest_id, Document doc) throws SQLException {
        String s = doc.getElementById("main-contents").children().first().html();
        int start = s.indexOf("\"breadcrumb\":[") + "\"breadcrumb\":[".length();
        s = s.substring(start, s.indexOf(']', start));
        String[] split = s.split(",");
        if (split.length == 3) 
            return -2;
        return Integer.parseInt(s.split(",")[3]); 
    }
    
    static int get_flags(int quest_id, Document doc) throws SQLException {
        int flags = 0;
        String str = doc.getElementById("infobox-contents0").nextElementSibling().html();
        if (str.contains("Тип: [tooltip=tooltip_dailyquest]Ежедневно"))
            flags = 4096;
        if (str.contains("Тип: Раз в неделю"))
            flags |= 32768;
        return flags;
    }
    
    static int get_special_flags(Document doc) {
        int special_flags = 0;
        String s = doc.getElementById("infobox-contents0").nextElementSibling().html();
        if (s.contains("[li]Повторяемый[/li]"))
            special_flags = 1;
        if (s.contains("Тип: Ежемесячно"))
            special_flags |= 16;
        return special_flags;
    }
    
    static final Pattern skill_pattern = Pattern.compile("Профессия:\\s\\[skill=[0-9]+\\](\\s\\([0-9]+\\))?");
    static final Pattern skill_id_pattern = Pattern.compile("skill=[0-9]+");
    static final Pattern skill_req_points_pattern = Pattern.compile("\\([0-9]+\\)");
    
    static int[] get_required_skill(int id, Document doc) throws SQLException {
        String s = doc.getElementById("infobox-contents0").nextElementSibling().html();
        Matcher m = skill_pattern.matcher(s);
        if (!m.find())
            return new int[] {0, 0};
        else {
            s = s.substring(m.start(), m.end());
            m = skill_id_pattern.matcher(s);
            m.find();
            String[] split = s.substring(m.start(), m.end()).split("=");
            int skill_id = Integer.parseInt(split[1]);
            m = skill_req_points_pattern.matcher(s);
            if (m.find()) {
                int req_points = Integer.parseInt(s.substring(m.start(), m.end()).replaceAll("\\(|\\)", ""));
                return new int[] {skill_id, req_points};
            } else {
                Statement st = jdbc_connection.createStatement(); 
                ResultSet rs = st.executeQuery("SELECT RequiredSkillPoints FROM quest_template_addon WHERE ID = " + id + ";");
                if (rs.next()) {
                    return new int[] {skill_id, rs.getInt(1)};
                } else
                    return new int[] {skill_id, 1};
            }
        }
    }
    
    static final int cond_reputation_rank   = 5;
    static final int cond_questrewarderd    = 8;
    static final int cond_questtaken        = 9;
    static final int cond_quest_none        = 14;
    static final int cond_quest_complete    = 28;
    
    static QuestConditions get_conditions(int quest_id, Document doc) {
        Element e = doc.getElementById("tab-conditions");
        if (e == null)
            return null;
        String s = e.children().first().html();
        s = s.substring(37, s.length() - 76);
        JsonReader r = new JsonReader(new StringReader(s));
        r.setLenient(true);
        JsonObject obj = gson.fromJson(r, JsonObject.class);
        JsonElement el = obj.get("19");
        if (el == null)
            return null;
        JsonArray arr = el.getAsJsonObject().get(quest_id + "").getAsJsonArray();
        QuestConditions res = new QuestConditions();
        for (JsonElement else_group_elem : arr) { // for each else-group
            List<int[]> else_group = new ArrayList<>();
            JsonArray else_group_arr = else_group_elem.getAsJsonArray();
            for (JsonElement condition_elem : else_group_arr) { // for each cond in else-group
                JsonArray condition_arr = condition_elem.getAsJsonArray();
                int cond = condition_arr.get(0).getAsInt();
                int cond_type = abs(cond);
                switch (cond_type) {
                    case cond_reputation_rank:
                        int faction_id = condition_arr.get(1).getAsInt();
                        int rank_id = condition_arr.get(2).getAsInt();
                        else_group.add(new int[] {cond, faction_id, rank_id});
                        break;
                    case cond_questrewarderd:
                    case cond_questtaken:
                    case cond_quest_none:
                    case cond_quest_complete:
                        int cond_quest = condition_arr.get(1).getAsInt();
                        else_group.add(new int[] {cond, cond_quest});
                        break;
                }
            }
            if (!else_group.isEmpty())
                res.conditions.add(else_group);
        }
        if (!res.conditions.isEmpty())
            return res;
        return null;
    }
    
    static List<Integer> get_prev_quests_in_chain(Document doc) {
        String name = get_name(doc);
        for (Element e : doc.getElementsByTag("b")) {
            if (e.html().equals(name)) {
                Element elem = e.parent().parent();
                if (e.parent().tag().getName().equals("span"))
                    elem = elem.parent();
                elem = elem.previousElementSibling();
                if (elem.html().equals("1"))
                    return Collections.EMPTY_LIST;
                elem = elem.parent().previousElementSibling();
                List<Integer> res = new ArrayList<>();
                do {
                    res.add(Integer.parseInt(elem.getElementsByTag("a").first().attr("href").substring("?quest=".length())));
                } while ((elem = elem.previousElementSibling()) != null);
                return res;
            } 
        }
        return Collections.EMPTY_LIST;
    }
    
    static final String required_quests = "Чтобы получить это задание, вы должны завершить все указанные задания";
    static final String quests_must_be_active = "Вы можете получить это задание, только когда эти задания доступны";
    static final String required_one_of_quests = "Чтобы получить это задание, необходимо выполнить одно из следующих заданий";
    static final String quests_finished_by_this_quest = "Завершив этот квест, вы не сможете выполнять эти квесты";
    
    static List<Integer> get_quests(Document doc, String type) {
        Elements elems = doc.getElementsByAttributeValue("title", type);
        if (elems.isEmpty())
            return Collections.EMPTY_LIST;
        Element e = elems.first();
        List<Integer> res = new ArrayList<>();
        e = e.parent().parent().nextElementSibling();
        for (Element elem : e.getElementsByTag("a")) {
            res.add(Integer.parseInt(elem.attr("href").substring("?quest=".length())));
        }
        return res;
    }
    
    static final Map<Integer, Quest> quests = new HashMap<>();
    
    static class Quest implements Serializable {
        
        static final long serialVersionUID = 4324652423894L;
        
        int id;
        String ru_title;
        int min_level, max_level;
        int zone_or_sort;
        int req_skill_id, req_skill_points;
        int allowable_races, allowable_classes;
        int prev_quest_in_chain;
        int positive_exclusive_group;
        List<Integer> req_quests = new ArrayList<>();
        List<Integer> req_one_of_quests = new ArrayList<>();
        int quest_must_be_active;
        int flags, special_flags;
        List<Integer> events_id; 
        
        boolean valid;
     
        Quest(int id) {
            this.id = id;
        }
        
    }
	
    static boolean is_seasonal(Quest q) {
        int n = q.zone_or_sort;
        return (n == -22 || n == -284 || n == -366 || n == -369 || n == -370 || n == -376 || n == -374 || n == -1002 || n == -1003 || n == -1001 || n == -1005) && ((q.special_flags & 1) == 0);
    }
    
    static final Map<Integer, String> ru_event_name = new HashMap<>();
    static PrintWriter logger;
    
    static {
        try {
            logger = new PrintWriter("log.txt");
        } catch (FileNotFoundException ex) {
            ex.printStackTrace();
            System.exit(0);
        }
    }
    
    static final int quest_type = 0;
    static final int npc_type = 1;
    static final int obj_type = 2;
    static final int event_type = 3;

    static final String[] types = {"quest", "npc", "object", "event"};

    static Document get_document(int type, int id) throws IOException {
        Document doc = null;
        String path = "cache/" + types[type];
        File f = new File(path);
        if (!f.exists())
            f.mkdirs();
        f = new File(path + "/" + id + ".html");
        if (!f.exists()) {
            boolean success = false;
            String url = "http://db.ezwow.org/?" + types[type] + "=" + id;
            while (!success) {
                try {
                    doc = Jsoup.connect(url).timeout(1000 * 2).header("Accept-Language", "ru-RU").userAgent("Mozilla/5.0 (Windows NT 6.1; rv:60.0) Gecko/20100101 Firefox/60.0").get();
                    success = true;
                } catch (IOException ex) {}
            }
            try (PrintWriter out = new PrintWriter(f)) {
                out.print(doc.toString());
                out.flush();
            }
        } else 
            doc = Jsoup.parse(f, "UTF-8");
        return doc;
    }
    
    static void parse_isengard_db() throws IOException, SQLException {
        Map<Integer, QuestStarter> npc_quest_starters = new HashMap<>();
        Map<Integer, QuestStarter> obj_quest_starters = new HashMap<>();
        for (Iterator<Quest> it = quests.values().iterator(); it.hasNext();) {
            Quest q = it.next();
            Document doc = get_document(quest_type, q.id);
            add_quest(q.id, npc_quest_starters, true, doc);
            add_quest(q.id, obj_quest_starters, false, doc);
        }
        quest_starters.addAll(npc_quest_starters.values());
        quest_starters.addAll(obj_quest_starters.values());
    }
    
    static void add_quest(int quest_id, Map<Integer, QuestStarter> quest_starters_map, boolean npc, Document doc) throws IOException, SQLException {
        List<Integer> quest_starters_ids = get_quest_starters(doc, npc);
        for (int qs_id : quest_starters_ids) {
            QuestStarter qs = quest_starters_map.get(qs_id);
            if (qs == null) {
                Document qs_doc = get_document(npc ? npc_type : obj_type, qs_id);
                List<Pair<Integer, float[]>> coords = get_coords(qs_doc);
                if (coords == null) {
                    continue; // this quest starter is useless for us
                }
                qs = new QuestStarter(qs_id, npc);
                qs.ru_name = get_name(qs_doc);
                qs.events_id = get_related_events(qs_doc);
                qs.coords = coords;
                String msg = "New quest starter parsed.  ID: " + qs_id + ", ru_name: " + qs.ru_name + ", related_events: " + qs.events_id + ", coords: " + coords_to_string(qs.coords) + "\n\n";
                System.out.println(msg);
                logger.append(msg);
                quest_starters_map.put(qs_id, qs);
            }
            qs.quests_started.add(quest_id);
        }
    }
    
    static String coords_to_string(List<Pair<Integer, float[]>> coords) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        Iterator<Pair<Integer, float[]>> it = coords.iterator();
        while (it.hasNext()) {
            Pair<Integer, float[]> next = it.next();
            sb.append("{zone: ").append(next.getKey()).append(", coords: (x: ").append(next.getValue()[0]).append(", y: ").append(next.getValue()[1]).append(")}");
            if (it.hasNext())
                sb.append(", ");
        }
        sb.append("]");
        return sb.toString();
    }
    
    static String get_name(Document doc) {
        return doc.getElementById("header-logo").child(1).html();
    }
    
    static boolean is_valid_quest(Document doc) {
        return !doc.toString().contains("пометили это задание как устаревшее — его нельзя получить или выполнить.");
    }

    static final Pattern event = Pattern.compile("event=[0-9]+");
    
    static List<Integer> get_related_events(Document doc) throws IOException {
        int offset = "event=".length();
        List<Integer> res = new ArrayList<>();
        String s = doc.getElementById("infobox-contents0").nextElementSibling().html();
        Matcher m = event.matcher(s);
        while (m.find()) 
            res.add(Integer.parseInt(s.substring(m.start() + offset, m.end())));
        for (int event_id : res) {
            if (ru_event_name.containsKey(event_id))
                continue;
            String event_name = get_name(get_document(event_type, event_id));
            ru_event_name.put(event_id, event_name);
            String msg = "New event parsed. id: " + event_id + ", ru_name: " + event_name + "\n\n";
            System.out.println(msg);
            logger.append(msg);
        }
        return res.isEmpty() ? Collections.EMPTY_LIST : res;
    }
    
    static final Pattern npc = Pattern.compile("npc=[0-9]+");
    static final Pattern obj = Pattern.compile("object=[0-9]+");
    
    static List<Integer> get_quest_starters(Document doc, boolean collect_npc) throws SQLException {
        int offset = collect_npc ? "npc=".length() : "object=".length();
        String s = doc.getElementById("infobox-contents0").nextElementSibling().html();
        int start = s.indexOf("Начало: ");
        if (start == -1)
            return Collections.EMPTY_LIST;
        int end = s.indexOf("Конец: ");
        if (end == -1)
            end = s.length();
        List<Integer> res = new ArrayList<>();
        s = s.substring(start, end);
        Matcher m = collect_npc ? npc.matcher(s) : obj.matcher(s);
        while (m.find()) {
            int id = Integer.parseInt(s.substring(m.start() + offset, m.end()));
            res.add(id);
        }
        return res;
    }
    
    static final Gson gson = new Gson();
    
    static List<Pair<Integer, float[]>> get_coords(Document doc) {
        Element el = doc.getElementById("mapper-generic");
        if (el == null)
            return null;
        String s = el.nextElementSibling().nextElementSibling().html();
        s = s.substring(s.indexOf('{'), s.indexOf("var myMapper") - 1);
        JsonReader r = new JsonReader(new StringReader(s));
        r.setLenient(true);
        List<Pair<Integer, float[]>> res = new ArrayList<>();
        JsonObject object = gson.fromJson(r, JsonObject.class);
        object.entrySet().forEach(e -> {
            int map = Integer.parseInt(e.getKey());
            JsonElement elem = e.getValue();
            float x = -1f, y = -1f;
            if (elem.getClass() == JsonArray.class) { 
                JsonArray arr = (elem.getAsJsonArray()).get(0).getAsJsonObject().get("coords").getAsJsonArray().get(0).getAsJsonArray();
                x = arr.get(0).getAsFloat();
                y = arr.get(1).getAsFloat();
            } else { // json object, npc is in the dungeon (like deadmines)
                JsonObject obj = elem.getAsJsonObject();
                for (Map.Entry<String, JsonElement> v : obj.entrySet()) {
                    JsonArray arr = v.getValue().getAsJsonObject().get("coords").getAsJsonArray().get(0).getAsJsonArray();
                    x = arr.get(0).getAsFloat();
                    y = arr.get(1).getAsFloat();
                    break;
                }
            }
            res.add(new Pair(map, new float[] {x, y}));
        });
        return res;
    }
    
    static final List<QuestStarter> quest_starters = new ArrayList<>();
    
//  key is the zone_id and value is map where key is handy notes addon coords and value is list of quest starters (objects and npcs) at this coords
    static final Map<Integer, Map<Long, List<QuestStarter>>> zones = new HashMap<>();
    
    static void process_quest_starters() {
        for (QuestStarter qs : quest_starters) {
            for (Pair<Integer, float[]> coords : qs.coords) {
                int map = coords.getKey();
                Map<Long, List<QuestStarter>> all = zones.get(map);
                if (all == null)
                    zones.put(map, all = new HashMap<>());
                long hash = to_handy_notes_hash(coords.getValue()[0] / 100f, coords.getValue()[1] / 100f);
                List<QuestStarter> l = all.get(hash);
                if (l == null)
                    all.put(hash, l = new ArrayList<>());
                l.add(qs);
            }
        }
    }
    
    static void all_to_lua_table() throws IOException {
        PrintWriter pw = new PrintWriter(new File("Quests.lua"));
        pw.append("Q_RU_NAME_IDX = 1\n");
        pw.append("MIN_LEVEL_IDX = 2\n");
        pw.append("MAX_LEVEL_IDX = 3\n");
        pw.append("ALLOWABLE_RACES_IDX = 4\n");
        pw.append("ALLOWABLE_CLASSES_IDX = 5\n");
        pw.append("FLAGS_IDX = 6\n");
        pw.append("SPECIAL_FLAGS_IDX = 7\n");
        pw.append("REQ_SKILL_ID_IDX = 8\n");
        pw.append("REQ_SKILL_POINTS_IDX = 9\n");
        pw.append("Q_RELATED_EVENT_IDX = 10\n");
        pw.append("SEASONAL_IDX = 11\n");
        pw.append("VALID_IDX = 12\n");
        pw.append("PREV_QUEST_IN_CHAIN_IDX = 13\n");
        pw.append("QUEST_MUST_BE_ACTIVE_IDX = 14\n");
        pw.append("POSITIVE_EXCLUSIVE_GROUP_IDX = 15\n");
        pw.append("REQUIRED_QUESTS_IDX = 16\n");
        pw.append("REQUIRED_ONE_OF_QUESTS_IDX = 17\n\n");
        pw.append("Quests = {\n");
        for (Quest q : quests.values()) {
            pw.append("\t[").append(q.id + "] = {");
            pw.append(q.ru_title == null ? "0" : "\"" + q.ru_title.replaceAll("\"", "\\\\\"")).append("\", ");
            pw.append(q.min_level + ", ");
            pw.append(q.max_level + ", ");
            pw.append(q.allowable_races + ", ");
            pw.append(q.allowable_classes + ", ");
            pw.append(q.flags + ", ");
            pw.append(q.special_flags + ", ");
            pw.append(q.req_skill_id + ", ");
            pw.append(q.req_skill_points + ", ");
            pw.append(q.events_id.isEmpty() ? "0" : q.events_id.get(0).toString()).append(", ");
            pw.append(is_seasonal(q) ? "1" : "0").append(", ");
            pw.append(q.valid ? "1" : "0").append(", ");
            pw.append(q.prev_quest_in_chain + "").append(", ");
            pw.append(q.quest_must_be_active + "").append(", ");
            pw.append(q.positive_exclusive_group + "").append(", ");
            pw.append(q.req_quests.isEmpty() ? "0" : q.req_quests.toString().replace('[', '{').replace(']', '}')).append(", ");
            pw.append(q.req_one_of_quests.isEmpty() ? "0" : q.req_one_of_quests.toString().replace('[', '{').replace(']', '}'));
            pw.append("},\n");
        }
        pw.append("}").flush();
        pw = new PrintWriter(new File("RU_Events.lua"));
        pw.append("RU_Event = {\n");
        for (Map.Entry<Integer, String> e : ru_event_name.entrySet()) {
            pw.append("\t[").append(e.getKey().toString()).append("] = \"").append(e.getValue().replaceAll("\"", "\\\\\"")).append("\",\n");
        }
        pw.append("}").flush();
        pw = new PrintWriter(new File("QuestStarters.lua"));
        pw.append("QS_TYPE_NPC = 1\n");
        pw.append("QS_TYPE_OBJECT = 2\n\n");
        pw.append("QS_RU_NAME_IDX = 1\n");
        pw.append("QUESTS_STARTED_IDX = 2\n");
        pw.append("QS_RELATED_EVENT_IDX = 3\n\n");
        pw.append("GlobalQuestStarters = {\n");
        pw.append("\t[1] = {\n");
        for (QuestStarter qs : quest_starters) {
            if (qs.npc) {
                pw.append("\t\t[").append(qs.id + "").append("] = {");
                pw.append("\"").append(qs.ru_name.replaceAll("\"", "\\\\\"")).append("\", ");
                pw.append(qs.quests_started.toString().replace('[', '{').replace(']', '}')).append(", ");
                pw.append(qs.events_id.isEmpty() ? "0" : qs.events_id.size() > 1 ? "-1" : qs.events_id.get(0).toString());
                pw.append("},\n");
            }
        }
        pw.append("\t},\n");
        pw.append("\t[2] = {\n");
        for (QuestStarter qs : quest_starters) {
            if (!qs.npc) {
                pw.append("\t\t[").append(qs.id + "").append("] = {");
                pw.append("\"").append(qs.ru_name.replaceAll("\"", "\\\\\"")).append("\", ");
                pw.append(qs.quests_started.toString().replace('[', '{').replace(']', '}')).append(", ");
                pw.append(qs.events_id.isEmpty() ? "0" : qs.events_id.toString().replace('[', '{').replace(']', '}'));
                pw.append("},\n");
            }
        }
        pw.append("\t}\n");
        pw.append("}").flush();
        pw = new PrintWriter(new File("Zones.lua"));
        pw.append("Zones = {\n");
        for (Map.Entry<Integer, Map<Long, List<QuestStarter>>> e1 : zones.entrySet()) {
            pw.append("\t[").append(e1.getKey().toString()).append("] = {\n");
            for (Iterator<Map.Entry<Long, List<QuestStarter>>> it0 = e1.getValue().entrySet().iterator(); it0.hasNext();) {
                Map.Entry<Long, List<QuestStarter>> e2 = it0.next();
                pw.append("\t\t[").append(e2.getKey().toString()).append("] = {");
                for (Iterator<QuestStarter> it1 = e2.getValue().iterator(); it1.hasNext();) {
                    QuestStarter qs = it1.next();
                    pw.append("").append(qs.npc ? "1" : "2").append(", ").append(qs.id + "").append("");
                    if (it1.hasNext())
                        pw.append(", ");
                }
                pw.append("}");
                if (it0.hasNext())
                    pw.append(",");
                pw.append("\n");
            }
            pw.append("\t},\n");
        }
        pw.append("}").flush();
        pw = new PrintWriter(new File("QuestConditions.lua"));
        pw.append("QuestConditions = {\n");
        for (Map.Entry<Integer, QuestConditions> e : conditions.entrySet()) {
            pw.append("\t[").append(e.getKey().toString()).append("] = {");
            for (Iterator<List<int[]>> it1 = e.getValue().conditions.iterator(); it1.hasNext();) {
                List<int[]> l = it1.next();
                pw.append("{");
                for (Iterator<int[]> it2 = l.iterator(); it2.hasNext();) {
                    int[] i = it2.next();
                    if (abs(i[0]) == cond_reputation_rank) 
                        pw.append("{").append(i[0] + "").append(", ").append(i[1] + "").append(", ").append(i[2] + "").append("}");                        
                    else 
                        pw.append("{").append(i[0] + "").append(", ").append(i[1] + "").append("}");
                    if (it2.hasNext())
                        pw.append(",");
                }
                pw.append("}");
                if (it1.hasNext())
                    pw.append(",");
            }
            pw.append("}, \n");
        }
        pw.append("}").flush();
        pw = new PrintWriter(new File("PositiveExclusiveGroups.lua"));
        pw.append("PositiveExclusiveGroups = {\n");
        for (Map.Entry<Integer, List<Integer>> e : positive_eg.entrySet()) {
            pw.append('[').append(e.getKey().toString()).append("] = ").append(e.getValue().toString().replace('[', '{').replace(']', '}')).append(",\n");
        }
        pw.append("}").flush();
    }
    
    static long to_handy_notes_hash(float x, float y) {
        return (long) (floor(x * 10000f + 0.5f) * 10000f + floor(y * 10000f + 0.5f));
    }
    
    static class QuestStarter {
        
        int id;
        boolean npc;
        String ru_name;
        List<Pair<Integer, float[]>> coords;
        List<Integer> events_id;
        List<Integer> quests_started = new ArrayList<>();
        
        QuestStarter(int id, boolean npc) {
            this.id = id;
            this.npc = npc;
        }
        
    }
    
    static final Map<Integer, QuestConditions> conditions = new HashMap<>();
    
    static class QuestConditions {
        List<List<int[]>> conditions = new ArrayList<>();
    }
    
}
