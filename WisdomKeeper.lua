local WisdomKeeper = LibStub("AceAddon-3.0"):NewAddon("WisdomKeeper", "AceEvent-3.0", "AceHook-3.0")

local ClassMask = {
	["WARRIOR"] 	= 1,
	["PALADIN"] 	= 2,
	["HUNTER"] 		= 4,
	["ROGUE"] 		= 8,
	["PRIEST"] 		= 16,
	["DEATHKNIGHT"] = 32,
	["SHAMAN"] 		= 64,
	["MAGE"] 		= 128,
	["WARLOCK"] 	= 256,
	["DRUID"] 		= 1024
}

local RaceMask = {
	["Human"] 		= 1,
	["Orc"] 		= 2,
	["Dwarf"] 		= 4,
	["NightElf"] 	= 8,
	["Scourge"] 	= 16,
	["Tauren"] 		= 32,
	["Gnome"] 		= 64,
	["Troll"] 		= 128,
	["BloodElf"] 	= 512,
	["Draenei"] 	= 1024
}

----------------Some quest utils-----------------

local QUEST_REPEATABLE_SPECIAL_FLAG = 1
local QUEST_MONTHLY_SPECIAL_FLAG 	= 16

local QUEST_TRACKING_FLAG 	= 1024
local QUEST_DAILY_FLAG 		= 4096
local QUEST_WEEKLY_FLAG		= 32768

local function IsRepeatable(Quest)
	return bit.band(QUEST_REPEATABLE_SPECIAL_FLAG, Quest[SPECIAL_FLAGS_IDX]) ~= 0
end

local function IsDaily(Quest)
	return bit.band(QUEST_DAILY_FLAG, Quest[FLAGS_IDX]) ~= 0
end

local function IsWeekly(Quest) 
	return bit.band(QUEST_WEEKLY_FLAG, Quest[FLAGS_IDX]) ~= 0
end

local function IsMonthly(Quest)
	return bit.band(QUEST_MONTHLY_SPECIAL_FLAG, Quest[SPECIAL_FLAGS_IDX]) ~= 0
end

local function IsTracking(Quest)
	return bit.band(QUEST_TRACKING_FLAG, Quest[FLAGS_IDX]) ~= 0
end

local function CanIncreaseRewardedQuestCounters(Quest)
	return (not IsDaily(Quest) and (not IsRepeatable(Quest) or IsWeekly(Quest) or IsMonthly(Quest)))
end

local function UpdateTimeRewarded(Quest, QuestID)
	if (IsDaily(Quest)) then 
		DB.DailyQuests[QuestID] = time()
	elseif (IsWeekly(Quest)) then
		DB.WeeklyQuests[QuestID] = time()
	elseif (IsMonthly(Quest)) then
		DB.MonthlyQuests[QuestID] = time()
	end
end

---------------------------------------------------

local DB = nil

function WisdomKeeper:OnInitialize()

	local defaults = {
		["char"] = {
			["WasInitialized"] = false,
			["ActiveQuests"] = {},
			["RewardedQuests"] = {},
			["DailyQuests"] = {},
			["WeeklyQuests"] = {},
			["MonthlyQuests"] = {},
			["QuestNameToQuestID"] = {}
		}
	}
	
	self.db = LibStub("AceDB-3.0"):New("WisdomKeeperDBPC", defaults)
	DB = self.db["char"]
	
	self:RegisterEvent("PLAYER_ENTERING_WORLD")
	
end

function WisdomKeeper:OnEnable() end
function WisdomKeeper:OnDisable() end

function WisdomKeeper:PLAYER_ENTERING_WORLD()
	self:UnregisterEvent("PLAYER_ENTERING_WORLD")
	if (not DB.WasInitialized) then 
		self:InitialiazeCharacterDB()
	else 
		self:RegisterAllEvents()
	end
end

--------------The first launch of the addon. No character data yet.------------------

function WisdomKeeper:InitialiazeCharacterDB()

	local _, RaceName = UnitRace("player")
	local RaceMask = RaceMask[RaceName] 
	DB.RaceMask = RaceMask
	
	local _, PlayerClass = UnitClass("player")
	local ClassMask = ClassMask[PlayerClass]
	DB.ClassMask = ClassMask
	
	local Level = UnitLevel("player")
	DB.Level = Level
	
	self:RegisterEvent("QUEST_QUERY_COMPLETE")
	QueryQuestsCompleted()
	
end

function WisdomKeeper:QUEST_QUERY_COMPLETE()

	self:UnregisterEvent("QUEST_QUERY_COMPLETE")

	local CompletedQuests = GetQuestsCompleted({})
	for QuestID, _ in pairs(CompletedQuests) do
		local Quest = Quests[QuestID]
		if (Quest ~= nil) then 
			if (CanIncreaseRewardedQuestCounters(Quest)) then
				DB.RewardedQuests[QuestID] = true
			end
			UpdateTimeRewarded(Quest, QuestID)
		end
	end
	
	self:StoreActiveQuests()
	DB.WasInitialized = true
	
	self:RegisterAllEvents()

end

function WisdomKeeper:StoreActiveQuests()
	local NumEntries, _ = GetNumQuestLogEntries()
	for i = 1, NumEntries do
		local QuestName, _, _, _, _, _, _, _, QuestID = GetQuestLogTitle(i)
		local Quest = Quests[QuestID]
		if (Quest ~= nil) then
			DB.ActiveQuests[QuestID] = true
			DB.QuestNameToQuestID[QuestName] = QuestID
		end
	end
end

---------------------------------------------------------------

function WisdomKeeper:RegisterAllEvents()

	self:RegisterEvent("PLAYER_LEVEL_UP")
	self:RegisterEvent("QUEST_ACCEPTED")
	self:RegisterEvent("QUEST_COMPLETE")
	
	AbandonQuestSourceFunc = StaticPopupDialogs["ABANDON_QUEST"].OnAccept
	StaticPopupDialogs["ABANDON_QUEST"].OnAccept = function()
		self:QUEST_ABANDONED()
		AbandonQuestSourceFunc()
	end
	
	AbandonQuestWithItemsSourceFunc = StaticPopupDialogs["ABANDON_QUEST_WITH_ITEMS"].OnAccept
	StaticPopupDialogs["ABANDON_QUEST_WITH_ITEMS"].OnAccept = function()
		self:QUEST_ABANDONED()
		AbandonQuestWithItemsSourceFunc()
	end
	
	self:SecureHook("GetQuestReward", self.QUEST_TURNED_IN)
	
	HandyNotes:RegisterPluginDB("WisdomKeeper", self, nil)
	
end

function WisdomKeeper:PLAYER_LEVEL_UP(EventName, ...)
	local Args = {...}
	DB.Level = tonumber(Args[1])
	HandyNotes:UpdateMinimapPlugin("WisdomKeeper")
end

function WisdomKeeper:QUEST_ABANDONED() 
	local QuestName = GetAbandonQuestName()
	local QuestID = DB.QuestNameToQuestID[QuestName]
	if (QuestID ~= nil) then
		DB.ActiveQuests[QuestID] = nil
		DB.QuestNameToQuestID[QuestName] = nil
		HandyNotes:UpdateMinimapPlugin("WisdomKeeper")
	end
end

local QuestTitleTemp = ""

function WisdomKeeper:QUEST_COMPLETE() 
	QuestTitleTemp = GetTitleText()
end

function WisdomKeeper:QUEST_TURNED_IN(...)
	QuestID = DB.QuestNameToQuestID[QuestTitleTemp]
	local Quest = Quests[QuestID]
	if (Quest ~= nil) then 
		DB.ActiveQuests[QuestID] = nil
		
		local QuestName
		for QName, QID in pairs(DB.QuestNameToQuestID) do
			if (QID == QuestID) then
				QuestName = QName
				break
			end
		end
		DB.QuestNameToQuestID[QuestName] = nil
		
		if (CanIncreaseRewardedQuestCounters(Quest)) then
			DB.RewardedQuests[QuestID] = true
		end
		UpdateTimeRewarded(Quest, QuestID)
		HandyNotes:UpdateMinimapPlugin("WisdomKeeper")
	end
end

function WisdomKeeper:QUEST_ACCEPTED(EventName, QuestLogIndex)
	local QuestName, _, _, _, _, _, _, _, QuestID = GetQuestLogTitle(QuestLogIndex)
	local Quest = Quests[QuestID]
	if (Quest ~= nil) then
		if (IsTracking(Quest)) then 
			if (CanIncreaseRewardedQuestCounters(Quest)) then 
				DB.RewardedQuests[QuestID] = true;
			end
			UpdateTimeRewarded(Quest, QuestID)
		else 
			DB.ActiveQuests[QuestID] = true
			DB.QuestNameToQuestID[QuestName] = QuestID
		end
		HandyNotes:UpdateMinimapPlugin("WisdomKeeper")
	end
end

local RU_SkillNameToSkillID = {
	["Первая помощь"] 	= 129,
	["Кузнечное дело"] 	= 164,
	["Кожевничество"] 	= 165,
	["Алхимия"] 		= 171,
	["Травничество"] 	= 182,
	["Кулинария"] 		= 185,
	["Горное дело"] 	= 186,
	["Портняжное дело"] = 197,
	["Инженерное дело"] = 202,
	["Наложение чар"] 	= 333,
	["Рыбная ловля"] 	= 356,
	["Снятие шкур"] 	= 393,
	["Ювелирное дело"] 	= 755,
	["Верховая езда"] 	= 762
}

DAY_SECONDS 	= 24 * 60 * 60
WEEK_SECONDS 	= 7 * DAY_SECONDS
MONTH_SECONDS 	= 30.4167 * DAY_SECONDS --As Google says :)

function WisdomKeeper:SatisfyQuestDay(Quest, QuestID)
	if (not IsDaily(Quest) or DB.DailyQuests[QuestID] == nil) then return true end
	return time() - DB.DailyQuests[QuestID] > DAY_SECONDS
end

function WisdomKeeper:SatisfyQuestWeek(Quest, QuestID)
	if (not IsWeekly(Quest) or DB.WeeklyQuests[QuestID] == nil) then return true end
	return time() - DB.WeeklyQuests[QuestID] > WEEK_SECONDS_SECONDS
end

function WisdomKeeper:SatisfyQuestMonth(Quest, QuestID)
	if (not IsMonthly(Quest) or DB.MonthlyQuests[QuestID] == nil) then return true end
	return time() - DB.MonthlyQuests[QuestID] > MONTH_SECONDS
end

local QUEST_STATUS_NONE 		= 0 
local QUEST_STATUS_ACTIVE 		= 1 
local QUEST_STATUS_REWARDED 	= 2

function WisdomKeeper:GetQuestStatus(Quest, QuestID)
	if (DB.ActiveQuests[QuestID] ~= nil) then return QUEST_STATUS_ACTIVE end
	if (not IsRepeatable(Quest) and DB.RewardedQuests[QuestID] ~= nil) then return QUEST_STATUS_REWARDED end
	return QUEST_STATUS_NONE
end

function WisdomKeeper:CanTakeQuest(QuestID)
	local Quest = Quests[QuestID]
	if (not self:SatisfyQuestStatus(Quest, QuestID)) then return false end
	if (not self:SatisfyQuestExclusiveGroup(Quest, QuestID)) then return false end
	if (not self:SatisfyQuestClass(Quest)) then return false end
	if (not self:SatisfyQuestRace(Quest)) then return false end
	if (not self:SatisfyQuestLevel(Quest)) then return false end
	if (not self:SatisfyQuestSkill(Quest)) then return false end
	if (not self:SatisfyQuestReputation(Quest)) then return false end
	if (not self:SatisfyQuestPreviousQuest(Quest)) then return false end
	if (not self:SatisfyQuestNextChain(Quest)) then return false end
	if (not self:SatisfyQuestPrevChain(Quest)) then return false end
	if (not self:SatisfyQuestDay(Quest, QuestID)) then return false end
	if (not self:SatisfyQuestWeek(Quest, QuestID)) then return false end
	if (not self:SatisfyQuestMonth(Quest, QuestID)) then return false end
	return true
end

function WisdomKeeper:SatisfyQuestStatus(Quest, QuestID)
	local Status = self:GetQuestStatus(Quest, QuestID)
	return Status == QUEST_STATUS_NONE 
end

function WisdomKeeper:SatisfyQuestExclusiveGroup(Quest, QuestID) 
	local EG = Quest[EXCLUSIVE_GROUP_IDX]
	if (EG <= 0) then return true end
	local EGs = ExclusiveGroups[EG]
	for i = 1, #EGs do 
		local ExcludeID = EGs[i]
		if (ExcludeID ~= QuestID) then 
			local ExcludeQuest = Quests[ExcludeID]
			if (not self:SatisfyQuestDay(ExcludeQuest, ExcludeID) or not self:SatisfyQuestWeek(ExcludeQuest, ExcludeID)) then return false end
			if (self:GetQuestStatus(ExcludeQuest, ExcludeID) ~= QUEST_STATUS_NONE) or (not (IsRepeatable(Quest) and IsRepeatable(ExcludeQuest)) and DB.RewardedQuests[ExcludeID] ~= nil) then return false end
		end
	end
	return true
end

function WisdomKeeper:SatisfyQuestRace(Quest)
	if Quest[ALLOWABLE_RACES_IDX] ~= 0 then
		return bit.band(DB.RaceMask, Quest[ALLOWABLE_RACES_IDX]) ~= 0
	end 
	return true
end

function WisdomKeeper:SatisfyQuestClass(Quest)
	if Quest[ALLOWABLE_CLASSES_IDX] ~= 0 then
		return bit.band(DB.ClassMask, Quest[ALLOWABLE_CLASSES_IDX]) ~= 0
	end
	return true
end

function WisdomKeeper:SatisfyQuestLevel(Quest)
	if (DB.Level < Quest[MIN_LEVEL_IDX]) then return false end
	if (Quest[MAX_LEVEL_IDX] > 0 and DB.Level > Quest[MAX_LEVEL_IDX]) then return false end
	return true
end

function WisdomKeeper:SatisfyQuestSkill(Quest)
	local SkillID = Quest[REQUIRED_SKILL_ID_IDX]
	if (SkillID == 0) then return true end
	for i = 1, GetNumSkillLines() do
		local SkillName, _, _, SkillValue = GetSkillLineInfo(i)
		local SID = RU_SkillNameToSkillID[SkillName]
		if (SID == SkillID) then return SkillValue >= Quest[REQUIRED_SKILL_POINTS_IDX] end
	end
	return false
end

function WisdomKeeper:SatisfyQuestReputation(Quest) 
	local ReqMinRepFaction = Quest[REQUIRED_MIN_REP_FACTION_IDX]
	if (ReqMinRepFaction ~= 0) then 
		local _, _, _, _, _, RepValue = GetFactionInfoByID(ReqMinRepFaction)
		if (RepValue < Quest[REQUIRED_MIN_REP_VALUE_IDX]) then return false end
	end
	local ReqMaxRepFaction = Quest[REQUIRED_MAX_REP_FACTION_IDX]
	if (ReqMaxRepFaction ~= 0) then
		local _, _, _, _, _, RepValue = GetFactionInfoByID(ReqMaxRepFaction)
		if (RepValue > Quest[REQUIRED_MAX_REP_VALUE_IDX]) then return false end
	end
	return true
end

function WisdomKeeper:SatisfyQuestPreviousQuest(Quest)
	local PrevQuests = Quest[PREV_QUESTS_IDX]
	if (#PrevQuests == 0) then return true end	
	for i = 1, #PrevQuests do 
		local PrevID = abs(PrevQuests[i])
		local PrevQuest = Quests[PrevID]
		if (PrevQuests[i] > 0 and DB.RewardedQuests[PrevID] ~= nil) then 
			if (PrevQuest[EXCLUSIVE_GROUP_IDX] >= 0) then return true end
			local EGs = ExclusiveGroups[PrevQuest[EXCLUSIVE_GROUP_IDX]]
			for j = 1, #EGs do 
				local ExcludeID = EGs[j]
				if (ExcludeID ~= PrevID) then 
					if (DB.RewardedQuests[ExcludeID] == nil) then return false end
				end
			end
			return true
		end
		if (PrevQuests[i] < 0 and self:GetQuestStatus(PrevQuest, PrevID) ~= QUEST_STATUS_NONE) then
			if (PrevQuest[EXCLUSIVE_GROUP_IDX] >= 0) then return true end
			local EGs = ExclusiveGroups[PrevQuest[EXCLUSIVE_GROUP_IDX]]
			for j = 1, #EGs do 
				local ExcludeID = EGs[j]
				if (ExcludeID ~= PrevID) then 
					if (self:GetQuestStatus(Quests[ExcludeID], ExcludeID) ~= QUEST_STATUS_NONE) then return false end
				end
			end
			return true
		end
	end
	return false
end

function WisdomKeeper:SatisfyQuestNextChain(Quest)
	local RewardNextQuestID = Quest[REWARD_NEXT_QUEST_IDX]
	if (RewardNextQuestID ~= 0) then
		local Status = self:GetQuestStatus(Quests[RewardNextQuestID], RewardNextQuest)
		if (Status ~= QUEST_STATUS_NONE) then return false end
	end
	return true
end

function WisdomKeeper:SatisfyQuestPrevChain(Quest)
	local PrevChainQuests = Quest[PREV_CHAIN_QUESTS_IDX]
	if (#PrevChainQuests ~= 0) then
		for i = 1, #PrevChainQuests do
			if (DB.ActiveQuests[PrevChainQuests[i]] ~= nil) then return false end
		end
	end
	return true
end

------- HandyNotes plugin methods -----------

local IconPath = "Interface\\AddOns\\WisdomKeeper\\icons\\QuestIcon"
local AvailableQuestsAt = nil

local function Iterate(ZoneQuestStarters, PrevHash)
	if (ZoneQuestStarters == nil) then return nil end
	Hash, QuestsStarted = next(ZoneQuestStarters, PrevHash)
	while (Hash ~= nil) do
		local AvailableQuests = {}
		for i = 1, #QuestsStarted do
			if (WisdomKeeper:CanTakeQuest(QuestsStarted[i])) then
				table.insert(AvailableQuests, QuestsStarted[i])
			end
		end
		if (#AvailableQuests ~= 0) then
			AvailableQuestsAt[Hash] = AvailableQuests
			return Hash, nil, IconPath, 1.3, 1.0
		end
		Hash, QuestsStarted = next(ZoneQuestStarters, Hash)
	end
	return nil, nil, nil, nil
end

function WisdomKeeper:GetNodes(MapFile, MiniMap, DungeonLevel)
	AvailableQuestsAt = {}
	local ZoneIndex = MapFileToZoneIndex[MapFile]
	return Iterate, QuestStarters[ZoneIndex], nil
end

function WisdomKeeper:OnEnter(MapFile, Hash)
	self:SetBackdropColor(0, 0, 0, 1)
	local Tooltip = self:GetParent() == WorldMapButton and WorldMapTooltip or GameTooltip
	self.Tooltip = Tooltip
	if (self:GetCenter() > UIParent:GetCenter()) then 
		Tooltip:SetOwner(self, "ANCHOR_LEFT")
	else
		Tooltip:SetOwner(self, "ANCHOR_RIGHT")
	end
	local AvailableQuests = AvailableQuestsAt[Hash]
	for i = 1, #AvailableQuests do
		local QuestID = AvailableQuests[i]
		local Quest = Quests[QuestID]
		local QuestName = Quest[TITLE_IDX]
		local ShowString = "QUEST: " .. QuestName .. ", ID: " .. QuestID
		if (Quest[IS_EVENT_QUEST_IDX] == 1) then ShowString = ShowString .. " (EVENT QUEST)" end
		Tooltip:AddLine(ShowString, 1, 1, 0)
	end
	Tooltip:Show()
end

function WisdomKeeper:OnLeave(MapFile, Hash)
	self.Tooltip:Hide()
end


---------------------------------------------

