package wisdomkeeperdbcreating;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import static java.lang.Math.abs;
import static java.lang.Math.floor;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javafx.util.Pair;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 *
 * @author Texhnolyze
 */
public class DBCreating {
    
    static final String jdbc_url = "jdbc:mysql://localhost:3306/world";
    static final String user = "root", password = "admin";
    
    public static void main(String[] args) throws SQLException, IOException {
        collect_all_quests();
        process_quests();
        parse_wow_circle();
        process_quest_starters();
        all_to_lua_table();
    }
    
    static void collect_all_quests() throws SQLException {
        Connection c = DriverManager.getConnection(jdbc_url, user, password);
        Statement s = c.createStatement();  // 1     2              3          4           5           6
        ResultSet rs = s.executeQuery("SELECT id, MinLevel, QuestSortID, RewardNextQuest, Flags, AllowableRaces FROM quest_template;");
        while (rs.next()) {
            int id = rs.getInt(1);
            int min_level = rs.getInt(2);
			int zone_or_sort = rs.getInt(3);
            int reward_next_quest = rs.getInt(4);
            int flags = rs.getInt(5);
            int allowable_races = rs.getInt(6);
            quests.put(id, new Quest(id, min_level, zone_or_sort, reward_next_quest, flags, allowable_races));
        }                       //  1       2             3              4            5             6                7                  8                      9                      10                   11                   12               13
        rs = s.executeQuery("SELECT id, MaxLevel, AllowableClasses, PrevQuestID, NextQuestID, ExclusiveGroup, RequiredSkillID, RequiredSkillPoints, RequiredMinRepFaction, RequiredMaxRepFaction, RequiredMinRepValue, RequiredMaxRepValue, SpecialFlags FROM quest_template_addon;");
        while (rs.next()) {
			int id = rs.getInt(1);
			int special_flags = rs.getInt(13);
			if ((special_flags & 8) != 0) {
				quests.remove(id);
				continue; // we don't need DF (dungeon find) quests
			}
            Quest q = quests.get(id);
            q.max_level = rs.getInt(2);
            q.allowable_classes = rs.getInt(3);
            q.prev_quest_id = rs.getInt(4);
            q.next_quest_id = rs.getInt(5);
            q.exclusive_group = rs.getInt(6);
            q.req_skill_id = rs.getInt(7);
            q.req_skill_points = rs.getInt(8);
            q.req_min_rep_faction = rs.getInt(9);
            q.req_max_rep_faction = rs.getInt(10);
            q.req_min_rep_value = rs.getInt(11);
            q.req_max_rep_value = rs.getInt(12);
            q.special_flags = special_flags;
        }
		rs = s.executeQuery("SELECT * FROM game_event_seasonal_questrelation;");
		while (rs.next()) {
			quests.get(rs.getInt(1)).event_id_for_quest = rs.getInt(2);
		}
    }
    
    static void process_quests() {
        Iterator<Quest> it = quests.values().iterator();
        while (it.hasNext()) {
            Quest q = it.next();
            if (q.reward_next_quest != 0 && !quests.containsKey(q.reward_next_quest)) {
                throw new RuntimeException();
            }
            if (q.prev_quest_id != 0 && !quests.containsKey(abs(q.prev_quest_id))) {
                throw new RuntimeException();
            }
            if (q.next_quest_id != 0) {
                Quest next_q = quests.get(q.next_quest_id);
                if (next_q == null)
                    throw new RuntimeException();
                next_q.dependent_quests.add(q.id);
            }
            if (q.exclusive_group != 0) {
                List<Integer> exclusive_group = exclusive_groups.get(q.exclusive_group);
                if (exclusive_group == null)
                    exclusive_groups.put(q.exclusive_group, exclusive_group = new ArrayList<>());
                exclusive_group.add(q.id);
            }
        }
    }
    
    static final Map<Integer, Quest> quests = new HashMap<>();
    static final Map<Integer, List<Integer>> exclusive_groups = new HashMap<>();
    
    static class Quest {
        
        int id;
        String ru_title;
        int min_level, max_level;
		int zone_or_sort;
        int req_min_rep_faction, req_max_rep_faction;
        int req_min_rep_value, req_max_rep_value;
        int req_skill_id, req_skill_points;
        int allowable_races, allowable_classes;
        int exclusive_group;
        int prev_quest_id;
        int next_quest_id;
        int reward_next_quest;
        List<Integer> dependent_quests = new ArrayList<>();
        int flags, special_flags;
        List<Integer> events_id; // from wow - circle
		int event_id_for_quest;
        
        boolean valid;
     
        Quest(int id, int min_level, int zone_or_sort, int reward_next_quest, int flags, int allowable_races) {
            this.id = id;
            this.min_level = min_level;
			this.zone_or_sort = zone_or_sort;
            this.reward_next_quest = reward_next_quest;
            this.flags = flags;
            this.allowable_races = allowable_races;
        }
        
    }
	
	static boolean is_seasonal(Quest q) {
		int n = q.zone_or_sort;
		return (n == -22 || n == -284 || n == -366 || n == -369 || n == -370 || n == -376 || n == -374) && ((q.special_flags & 1) == 0);
	}
    
    static final Map<Integer, String> ru_event_name = new HashMap<>();
    
    static void parse_wow_circle() throws IOException {
        Map<Integer, QuestStarter> npc_quest_starters = new HashMap<>();
        Map<Integer, QuestStarter> obj_quest_starters = new HashMap<>();
        for (Iterator<Quest> it = quests.values().iterator(); it.hasNext();) {
            Quest q = it.next();
            Document doc = Jsoup.connect("https://db.ezwow.org/?quest=" + q.id).get();
            if (doc.getElementById("inputbox-error") != null) {
                System.out.println("ERROR: " + q.id);
                e.events_id = Collections.EMPTY_LIST;
				continue; //wow-circle db contains no information about this quest
            }
            q.ru_title = get_name(doc);
            q.valid = is_valid_quest(doc);
            q.events_id = get_related_events(doc);
            if (!add_quest(q.id, npc_quest_starters, true, doc) & !add_quest(q.id, obj_quest_starters, false, doc))
                it.remove(); // no npcs or objects that starts this quest, remove it.
        }
        quest_starters.addAll(npc_quest_starters.values());
        quest_starters.addAll(obj_quest_starters.values());
    }
    
    static boolean add_quest(int quest_id, Map<Integer, QuestStarter> quest_starters_map, boolean npc, Document doc) throws IOException {
        List<Integer> quest_starters_ids = get_quest_starters(doc, npc);
        boolean b = false;
        for (int qs_id : quest_starters_ids) {
            QuestStarter qs = quest_starters_map.get(qs_id);
            if (qs == null) {
                Document qs_doc = Jsoup.connect("https://db.ezwow.org/?" + (npc ? "npc=" : "object=") + qs_id).get();
                List<Pair<Integer, float[]>> coords = get_coords(qs_doc);
                if (coords == null)
                    continue; // this quest starter is useless for us
                qs = new QuestStarter(qs_id, npc);
                qs.ru_name = get_name(qs_doc);
                qs.events_id = get_related_events(qs_doc);
                qs.coords = coords;
                quest_starters_map.put(qs_id, qs);
            }
            qs.quests_started.add(quest_id);
            b = true;
        }
        return b;
    }
    
    static String get_name(Document doc) {
        return doc.getElementById("header-logo").child(1).html();
    }
    
    static boolean is_valid_quest(Document doc) {
        String s = doc.getElementById("infobox-contents0").nextElementSibling().html();
        return !s.contains("disabledHint") && !s.contains("Недоступно игрокам");
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
            ru_event_name.put(event_id, get_name(Jsoup.connect("https://db.ezwow.org/?event=" + event_id).get()));
        }
        return res.isEmpty() ? Collections.EMPTY_LIST : res;
    }
    
    static final Pattern npc = Pattern.compile("npc=[0-9]+");
    static final Pattern obj = Pattern.compile("object=[0-9]+");
    
    static List<Integer> get_quest_starters(Document doc, boolean collect_npc) {
        int offset = collect_npc ? "npc=".length() : "object=".length();
        String s = doc.getElementById("infobox-contents0").nextElementSibling().html();
        int start = s.indexOf("Начало: ");
        if (start == -1)
            return Collections.EMPTY_LIST;
        List<Integer> res = new ArrayList<>();
        s = s.substring(start, s.indexOf("Конец: "));
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
            JsonArray arr = ((JsonArray) e.getValue()).get(0).getAsJsonObject().get("coords").getAsJsonArray().get(0).getAsJsonArray();
            float x = arr.get(0).getAsFloat();
            float y = arr.get(1).getAsFloat();
            res.add(new Pair(map, new float[] {x, y}));
        });
        return res;
    }
    
    static final List<QuestStarter> quest_starters = new ArrayList<>();
    
//  key is the zone_id and value is map where key is handy notes addon coords and value is list of quest starters (objects and npcs) at this coords
    static final Map<Integer, Map<Integer, List<QuestStarter>>> zones = new HashMap<>();
    
    static void process_quest_starters() {
        for (QuestStarter qs : quest_starters) {
            for (Pair<Integer, float[]> coords : qs.coords) {
                int map = coords.getKey();
                Map<Integer, List<QuestStarter>> all = zones.get(map);
                if (all == null)
                    zones.put(map, all = new HashMap<>());
                int hash = to_handy_notes_hash(coords.getValue()[0], coords.getValue()[1]);
                List<QuestStarter> l = all.get(hash);
                if (l == null)
                    all.put(hash, l = new ArrayList<>());
                l.add(qs);
            }
        }
    }
    
    static void all_to_lua_table() throws IOException {
        PrintWriter pw = new PrintWriter(new File("Quests.lua"));
        pw.append("RU_TITLE_IDX = 1\n");
        pw.append("MIN_LEVEL_IDX = 2\n");
        pw.append("MAX_LEVEL_IDX = 3\n");
        pw.append("ALLOWABLE_RACES_IDX = 4\n");
        pw.append("ALLOWABLE_CLASSES_IDX = 5\n");
        pw.append("FLAGS_IDX = 6\n");
        pw.append("SPECIAL_FLAGS_IDX = 7\n");
        pw.append("REQ_MIN_REP_FACTION_IDX = 8\n");
        pw.append("REQ_MAX_REP_FACTION_IDX = 9\n");
        pw.append("REQ_MIN_REP_VALUE_IDX = 10\n");
        pw.append("REQ_MAX_REP_VALUE_IDX = 11\n");
        pw.append("REQ_SKILL_ID_IDX = 12\n");
        pw.append("REQ_SKILL_POINTS_IDX = 13\n");
        pw.append("EXCLUSIVE_GROUP_IDX = 14\n");
        pw.append("PREV_QUEST_IDX = 15\n");
        pw.append("DEPENDENT_QUESTS_IDX = 16\n");
        pw.append("RELATED_EVENTS_IDX = 17\n");
		pw.append("SEASONAL_IDX = 18\n");
		pw.append("EVENT_FOR_QUEST_IDX = 19\n");
        pw.append("VALID_IDX = 20\n\n");
        pw.append("Quests = {\n");
        for (Quest q : quests.values()) {
            pw.append("\t").append(q.id + " = {");
            pw.append(q.ru_title == null ? "nil" : q.ru_title).append(", ");
            pw.append(q.min_level + ", ");
            pw.append(q.max_level + ", ");
            pw.append(q.allowable_races + ", ");
            pw.append(q.allowable_classes + ", ");
            pw.append(q.flags + ", ");
            pw.append(q.special_flags + ", ");
            pw.append(q.req_min_rep_faction + ", ");
            pw.append(q.req_max_rep_faction + ", ");
            pw.append(q.req_min_rep_value + ", ");
            pw.append(q.req_max_rep_value + ", ");
            pw.append(q.req_skill_id + ", ");
            pw.append(q.req_skill_points + ", ");
            pw.append(q.exclusive_group + ", ");
            pw.append(q.prev_quest_id + ", ");
            pw.append(q.dependent_quests.isEmpty() ? "nil" : q.dependent_quests.toString().replace('[', '{').replace(']', '}') + ", ");
            pw.append(q.events_id.isEmpty() ? "nil" : q.events_id.toString().replace('[', '{').replace(']', '}')).append(", ");
            pw.append(is_seasonal(q) ? "1" : "0").append(", ");
			pw.append(q.event_id_for_quest + "").append(", ");
			pw.append(q.valid ? "1" : "0");
            pw.append("},\n");
        }
        pw.append("}").flush();
        pw = new PrintWriter(new File("ExclusiveGroups.lua"));
        pw.append("ExclusiveGroups = {\n");
        for (Map.Entry<Integer, List<Integer>> e : exclusive_groups.entrySet()) {
            pw.append("\t[").append(e.getKey().toString()).append("] = ");
            pw.append(e.getValue().toString().replace('[', '{').replace(']', '}')).append(",\n");
        }
        pw.append("}").flush();
        pw = new PrintWriter(new File("RU_Events.lua"));
        pw.append("RU_Event = {\n");
        for (Map.Entry<Integer, String> e : ru_event_name.entrySet()) {
            pw.append("\t[").append(e.getKey().toString()).append("] = ").append(e.getValue()).append(", \n");
        }
        pw.append("}").flush();
        pw = new PrintWriter(new File("QuestStarters.lua"));
        pw.append("RU_NAME_IDX = 1\n");
        pw.append("QUESTS_STARTED_IDX = 2\n");
        pw.append("RELATED_EVENTS_IDX = 3\n");
        pw.append("QuestStarters = {\n");
        pw.append("\t[1] = {\n");
        for (QuestStarter qs : quest_starters) {
            if (qs.npc) {
                pw.append("\t\t[").append(qs.id + "").append("] = {");
                pw.append(qs.ru_name).append(", ");
                pw.append(qs.quests_started.toString().replace('[', '{').replace(']', '}')).append(", ");
                pw.append(qs.events_id.isEmpty() ? "nil" : qs.events_id.toString().replace('[', '{').replace(']', '}'));
                pw.append("},\n");
            }
        }
        pw.append("\t},\n");
        pw.append("\t[2] = {\n");
        for (QuestStarter qs : quest_starters) {
            if (!qs.npc) {
                pw.append("\t\t[").append(qs.id + "").append("] = {");
                pw.append(qs.ru_name).append(", ");
                pw.append(qs.quests_started.toString().replace('[', '{').replace(']', '}')).append(", ");
                pw.append(qs.events_id.isEmpty() ? "nil" : qs.events_id.toString().replace('[', '{').replace(']', '}'));
                pw.append("},\n");
            }
        }
        pw.append("\t}\n");
        pw.append("}").flush();
        pw = new PrintWriter(new File("Zones.lua"));
        pw.append("Zones = {\n");
        for (Map.Entry<Integer, Map<Integer, List<QuestStarter>>> e1 : zones.entrySet()) {
            pw.append("\t[").append(e1.getKey().toString()).append("] = {\n");
            for (Iterator<Map.Entry<Integer, List<QuestStarter>>> it0 = e1.getValue().entrySet().iterator(); it0.hasNext();) {
                Map.Entry<Integer, List<QuestStarter>> e2 = it0.next();
                pw.append("\t\t[").append(e2.getKey().toString()).append("] = {");
                for (Iterator<QuestStarter> it1 = e2.getValue().iterator(); it1.hasNext();) {
                    QuestStarter qs = it1.next();
                    pw.append("{").append(qs.npc ? "1" : "2").append(", ").append(qs.id + " ").append("}");
                    if (it1.hasNext())
                        pw.append(", ");
                }
                pw.append("}");
                if (it0.hasNext())
                    pw.append(",");
                pw.append("\n");
            }
            pw.append("},\n");
        }
        pw.append("}").flush();
    }
    
    static int to_handy_notes_hash(float x, float y) {
        return (int) (floor(x * 10000f + 0.5f) * 10000f + floor(y * 10000f + 0.5f));
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
    
}
