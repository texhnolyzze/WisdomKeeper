WisdomKeeper = LibStub("AceAddon-3.0"):NewAddon("WisdomKeeper", "AceEvent-3.0", "AceHook-3.0")
local DB = nil

function WisdomKeeper:SetEvent(ID) 
	self.db.realm.CurrentEventID = ID
end

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

local COMMON_MAN = {
	[19148] = true,
	[19169] = true,
	[19172] = true,
	[19176] = true,
	[18927] = true,
	[19175] = true,
	[19173] = true,
	[19178] = true,
	[19177] = true,
	[19171] = true,
	[20102] = true
}

local COMMON_MAN_RELATED_EVENTS = {
	[1] = true,
	[2] = true,
	[7] = true,
	[9] = true,
	[12] = true,
	[17] = true,
	[26] = true,
	[32] = true,
	[40] = true,
	[41] = true
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

local function IsSeasonal(Quest)
	return Quest[SEASONAL_IDX] ~= 0
end

local function IsTracking(Quest)
	return bit.band(QUEST_TRACKING_FLAG, Quest[FLAGS_IDX]) ~= 0
end

local function CanIncreaseRewardedQuestCounters(Quest)
	return not IsDaily(Quest) and (not IsRepeatable(Quest) or IsWeekly(Quest) or IsMonthly(Quest) or IsSeasonal(Quest))
end

local function UpdateTimeRewarded(Quest, QuestID)
	if (IsDaily(Quest)) then 
		DB.DailyQuests[QuestID] = time()
	elseif (IsWeekly(Quest)) then
		DB.WeeklyQuests[QuestID] = time()
	elseif (IsMonthly(Quest)) then
		DB.MonthlyQuests[QuestID] = time()
	elseif (IsSeasonal(Quest)) then
		DB.SeasonalQuests[QuestID] = time() -- I don't know how to use this information
	end
end

local function GetQuestNotInLogTitle()
	local NumEntries, _ = GetNumQuestLogEntries()
	for QuestID in pairs(DB.ActiveQuests) do
		local Flag = false
		for i = 1, NumEntries do
			local _, _, _, _, IsHeader, _, _, _, QID = GetQuestLogTitle(i)
			if (not IsHeader) then
				if (QID == QuestID) then
					Flag = true 
					break
				end
			end
		end
		if (not Flag) then return QuestID end
	end
	return -1
end

---------------------------------------------------

function WisdomKeeper:OnInitialize()
	local defaults = {
		["realm"] = {
			["CurrentEventID"] = 0
		}, 
		["char"] = {
			["WasInitialized"] = false,
			["ActiveQuests"] = {},
			["RewardedQuests"] = {},
			["DailyQuests"] = {},
			["WeeklyQuests"] = {},
			["MonthlyQuests"] = {},
			["SeasonalQuests"] = {}
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
		local _, _, _, _, IsHeader, _, _, _, QuestID = GetQuestLogTitle(i)
		if (not IsHeader) then
			if (Quests[QuestID] ~= nil) then
				DB.ActiveQuests[QuestID] = true
			end
		end
	end
end

---------------------------------------------------------------

function WisdomKeeper:RegisterAllEvents()
	HandyNotes:RegisterPluginDB("WisdomKeeper", self, nil)
	self:RegisterEvent("PLAYER_LEVEL_UP")
	self:RegisterEvent("QUEST_ACCEPTED")
	self:SecureHook("GetQuestReward", self.QUEST_TURNED_IN)
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
	self:RegisterEvent("QUEST_LOG_UPDATE")
end

function WisdomKeeper:PLAYER_LEVEL_UP(EventName, ...)
	local Args = {...}
	DB.Level = tonumber(Args[1])
	HandyNotes:UpdateMinimapPlugin("WisdomKeeper")
end

function WisdomKeeper:QUEST_ACCEPTED(EventName, QuestLogIndex)
	local _, _, _, _, _, _, _, _, QuestID = GetQuestLogTitle(QuestLogIndex)
	local Quest = Quests[QuestID]
	if (Quest ~= nil) then
		if (IsTracking(Quest)) then 
			if (CanIncreaseRewardedQuestCounters(Quest)) then 
				DB.RewardedQuests[QuestID] = true;
			end
			UpdateTimeRewarded(Quest, QuestID)
		else 
			DB.ActiveQuests[QuestID] = true
		end
		HandyNotes:UpdateMinimapPlugin("WisdomKeeper")
	end
end

local QUEST_NONE 	  = 0
local QUEST_ABANDONED = 1
local QUEST_TURNED_IN = 2

local State = QUEST_NONE

function WisdomKeeper:QUEST_ABANDONED()
	State = QUEST_ABANDONED
end

function WisdomKeeper:QUEST_TURNED_IN(...)
	State = QUEST_TURNED_IN
end

function WisdomKeeper:QUEST_LOG_UPDATE() 
	if (State == QUEST_ABANDONED) then 
		local AbandonedQuestID = GetQuestNotInLogTitle()
		if (AbandonedQuestID == -1) then return end
		DB.ActiveQuests[AbandonedQuestID] = nil
		HandyNotes:UpdateMinimapPlugin("WisdomKeeper")
		State = QUEST_NONE
	elseif (State == QUEST_TURNED_IN) then 
		local RewardedQuestID = GetQuestNotInLogTitle()
		if (RewardedQuestID == -1) then return end
		local RewardedQuest = Quests[RewardedQuestID]
		if (RewardedQuest ~= nil) then 
			DB.ActiveQuests[RewardedQuestID] = nil
			if (CanIncreaseRewardedQuestCounters(RewardedQuest)) then
				DB.RewardedQuests[RewardedQuestID] = true
			end
			UpdateTimeRewarded(RewardedQuest, RewardedQuestID)
			HandyNotes:UpdateMinimapPlugin("WisdomKeeper")
		end
		State = QUEST_NONE
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

local QUEST_STATUS_NONE 		= 0 
local QUEST_STATUS_INCOMPLETE 	= 1
local QUEST_STATUS_FAILED 		= 2
local QUEST_STATUS_COMPLETE		= 3 
local QUEST_STATUS_REWARDED 	= 4

function WisdomKeeper:GetQuestStatus(Quest, QuestID)
	if (DB.ActiveQuests[QuestID]) then 
		local NumEntries, _ = GetNumQuestLogEntries()
		for i = 1, NumEntries do
			local _, _, _, _, IsHeader, _, IsComplete, _, QID = GetQuestLogTitle(i)
			if (not IsHeader and QID == QuestID) then
				if (IsComplete == 1) then return QUEST_STATUS_COMPLETE 
				elseif (IsComplete == -1) then return QUEST_STATUS_FAILED 
				else return QUEST_STATUS_INCOMPLETE end
			end
		end
	end
	if (self:GetQuestRewardStatus(Quest, QuestID)) then return QUEST_STATUS_REWARDED end
	return QUEST_STATUS_NONE
end

function WisdomKeeper:GetQuestRewardStatus(Quest, QuestID)
	if (IsSeasonal(Quest)) then return (not self:SatisfyQuestSeasonal(Quest)) end
	if (not IsRepeatable(Quest)) then return self:IsQuestRewarded(QuestID) end
	return false
end

function WisdomKeeper:IsQuestRewarded(QuestID)
	return DB.RewardedQuests[QuestID] ~= nil 
end

function WisdomKeeper:CanTakeQuest(QuestID)
	local Quest = Quests[QuestID]
	if (Quest[VALID_IDX] == 0) then return false end
	if (Quest[Q_RELATED_EVENT_IDX] ~= 0 and self.db.realm.CurrentEventID ~= Quest[Q_RELATED_EVENT_IDX]) then return false end
	if (not self:SatisfyQuestStatus(Quest, QuestID)) then return false end
	if (not self:SatisfyQuestExclusiveGroup(Quest, QuestID)) then return false end
	if (not self:SatisfyQuestClass(Quest)) then return false end
	if (not self:SatisfyQuestRace(Quest)) then return false end
	if (not self:SatisfyQuestLevel(Quest)) then return false end
	if (not self:SatisfyQuestSkill(Quest)) then return false end
	if (not self:SatisfyQuestDependentQuests(Quest)) then return false end
	if (not self:SatisfyQuestDay(Quest, QuestID)) then return false end
	if (not self:SatisfyQuestWeek(Quest, QuestID)) then return false end
	if (not self:SatisfyQuestSeasonal(Quest, QuestID)) then return false end
	if (not self:SatisfyQuestMonth(Quest, QuestID)) then return false end
	if (not self:SatisfyQuestConditions(QuestID)) then return false end
	return true
end

function WisdomKeeper:SatisfyQuestStatus(Quest, QuestID)
	local Status = self:GetQuestStatus(Quest, QuestID)
	return Status == QUEST_STATUS_NONE 
end

function WisdomKeeper:SatisfyQuestExclusiveGroup(Quest, QuestID) 
	local EG = Quest[POSITIVE_EXCLUSIVE_GROUP_IDX]
	if (EG == 0) then return true end
	local EGs = PositiveExclusiveGroups[EG]
	for i = 1, #EGs do 
		local ExcludeID = EGs[i]
		if (ExcludeID ~= QuestID) then 
			local ExcludeQuest = Quests[ExcludeID]
			if (not self:SatisfyQuestDay(ExcludeQuest, ExcludeID) or not self:SatisfyQuestWeek(ExcludeQuest, ExcludeID) or not self:SatisfyQuestSeasonal(ExcludeQuest, ExcludeID)) then return false end
			if (self:GetQuestStatus(ExcludeQuest, ExcludeID) ~= QUEST_STATUS_NONE or (not (IsRepeatable(Quest) and IsRepeatable(ExcludeQuest)) and self:GetQuestRewardStatus(ExcludeQuest, ExcludeID))) then return false end
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
	local SkillID = Quest[REQ_SKILL_ID_IDX]
	if (SkillID == 0) then return true end
	for i = 1, GetNumSkillLines() do
		local SkillName, _, _, SkillValue = GetSkillLineInfo(i)
		local SID = RU_SkillNameToSkillID[SkillName]
		if (SID == SkillID) then return SkillValue >= Quest[REQ_SKILL_POINTS_IDX] end
	end
	return false
end

function WisdomKeeper:SatisfyQuestDependentQuests(Quest)
	local PrevQuestInChain = Quest[PREV_QUEST_IN_CHAIN_IDX]
	if (PrevQuestInChain ~= 0) then 
		if (DB.RewardedQuests[PrevQuestInChain] == nil) then return false end
	end
	local QuestMustBeActive = Quest[QUEST_MUST_BE_ACTIVE_IDX]
	if (QuestMustBeActive ~= 0) then 
		if (self:GetQuestStatus(Quests[QuestMustBeActive], QuestMustBeActive) ~= QUEST_STATUS_INCOMPLETE) then return false end
	end
	local RequiredQuests = Quest[REQUIRED_QUESTS_IDX]
	if (RequiredQuests ~= 0) then
		for i = 1, #RequiredQuests do
			if (not self:IsQuestRewarded(RequiredQuests[i])) then return false end
		end
	end
	local RequiredOneOfQuests = Quest[REQUIRED_ONE_OF_QUESTS_IDX]
	if (RequiredOneOfQuests ~= 0) then
		local Flag = false
		for i = 1, #RequiredOneOfQuests do
			if (self:IsQuestRewarded(RequiredOneOfQuests[i])) then
				Flag = true
				break
			end
		end
		if (not Flag) then return false end
	end
	return true
end

function WisdomKeeper:SatisfyQuestDay(Quest, QuestID)
	if (not IsDaily(Quest) or not DB.DailyQuests[QuestID]) then return true end
	return time() - DB.DailyQuests[QuestID] > DAY_SECONDS
end

function WisdomKeeper:SatisfyQuestWeek(Quest, QuestID)
	if (not IsWeekly(Quest) or not DB.WeeklyQuests[QuestID]) then return true end
	return time() - DB.WeeklyQuests[QuestID] > WEEK_SECONDS_SECONDS
end

function WisdomKeeper:SatisfyQuestMonth(Quest, QuestID)
	if (not IsMonthly(Quest) or not DB.MonthlyQuests[QuestID]) then return true end
	return time() - DB.MonthlyQuests[QuestID] > MONTH_SECONDS
end

function WisdomKeeper:SatisfyQuestSeasonal(Quest, QuestID)
	return true
end

local CONDITION_REPUTATION_RANK = 5
local CONDITION_QUESTREWARDED 	= 8
local CONDITION_QUESTTAKEN 		= 9
local CONDITION_QUEST_NONE		= 14
local CONDITION_QUEST_COMPLETE  = 28

function WisdomKeeper:SatisfyQuestConditions(QuestID)
	local Conditions = QuestConditions[QuestID]
	if (not Conditions) then return true end
	for i = 1, #Conditions do -- for each else-group
		local Flag = true
		for j = 1, #Conditions[i] do -- for each condition in else-group
			local Satisfied = false
			local ConditionType = Conditions[i][j][1]
			local AbsConditionType = abs(ConditionType)
			if (AbsConditionType == CONDITION_REPUTATION_RANK) then 
				local FactionID = Conditions[i][j][2]
				local ReputationRank = Conditions[i][j][3]
				_, _, RepRank = GetFactionInfoByID(FactionID)
				Satisfied = math.floor(math.pow(2, RepRank - 1)) >= ReputationRank
			else 
				local QID = Conditions[i][j][2]
				local Q = Quests[QID]
				if (AbsConditionType == CONDITION_QUESTREWARDED) then
					Satisfied = self:GetQuestRewardStatus(Q, QID)
				elseif (AbsConditionType == CONDITION_QUESTTAKEN) then
					Satisfied = self:GetQuestStatus(Q, QID) == QUEST_STATUS_INCOMPLETE
				elseif (AbsConditionType == CONDITION_QUEST_NONE) then
					Satisfied = self:GetQuestStatus(Q, QID) == QUEST_STATUS_NONE
				else 
					Satisfied = self:GetQuestStatus(Q, QID) == QUEST_STATUS_COMPLETE and not self:GetQuestRewardStatus(Q, QID)
				end
			end
			if (ConditionType < 0) then 
				Satisfied = not Satisfied 
			end
			if (not Satisfied) then 
				Flag = false
				break
			end
		end
		if (Flag) then return true end
	end
	return false
end

------- HandyNotes plugin methods -----------

local IconPath = "Interface\\AddOns\\WisdomKeeper\\icons\\QuestIcon"

function WisdomKeeper:GetNodes(MapFile, MiniMap, DungeonLevel)
	local Zone = Zones[MapFileToZoneIndex[MapFile]]
	if (not Zone) then return 
		function() -- empty function
			return nil
		end
	end
	local Hash, QuestStarters
	return function()
		Hash, QuestStarters = next(Zone, Hash)
		while (Hash ~= nil) do 
			for i = 1, #QuestStarters, 2 do
				local QuestStarterType = QuestStarters[i]
				local QuestStarterID = QuestStarters[i + 1]
				local QuestStarter = GlobalQuestStarters[QuestStarterType][QuestStarterID]
				if (QuestStarter[QS_RELATED_EVENT_IDX] == 0 or self.db.realm.CurrentEventID == QuestStarter[QS_RELATED_EVENT_IDX] or (COMMON_MAN[QuestStarterID] and COMMON_MAN_RELATED_EVENTS[self.db.realm.CurrentEventID])) then 
					local QuestsStarted = QuestStarter[QUESTS_STARTED_IDX]
					for j = 1, #QuestsStarted do
						local QuestID = QuestsStarted[j]
						if (self:CanTakeQuest(QuestID)) then
							return Hash, nil, IconPath, 1.3, 1.0 
						end
					end
				end
			end
			Hash, QuestStarters = next(Zone, Hash)
		end
		return nil
	end
end

local QuestStarterTypeToString = {
	[1] = "НПС",
	[2] = "Объект"
}

function WisdomKeeper:OnEnter(MapFile, Hash)
	self:SetBackdropColor(0, 0, 0, 1)
	local Tooltip = self:GetParent() == WorldMapButton and WorldMapTooltip or GameTooltip
	self.Tooltip = Tooltip
	if (self:GetCenter() > UIParent:GetCenter()) then 
		Tooltip:SetOwner(self, "ANCHOR_LEFT")
	else
		Tooltip:SetOwner(self, "ANCHOR_RIGHT")
	end
	local QuestStarters = Zones[MapFileToZoneIndex[MapFile]][Hash]
	Tooltip:AddLine("Здесь находятся:", 1, 1, 0)
	for i = 1, #QuestStarters, 2 do
		local QuestStarterType = QuestStarters[i]
		local QuestStarterID = QuestStarters[i + 1]
		local QuestStarter = GlobalQuestStarters[QuestStarterType][QuestStarterID]
		local QuestsStarted = QuestStarter[QUESTS_STARTED_IDX]
		local AvailableQuests = {}
		for j = 1, #QuestsStarted do
			local QuestID = QuestsStarted[j]
			if (WisdomKeeper:CanTakeQuest(QuestID)) then
				table.insert(AvailableQuests, QuestID)
			end
		end
		if (#AvailableQuests > 0) then 
			local StringToShow = QuestStarter[QS_RU_NAME_IDX] 
			StringToShow = StringToShow .. ", (" .. QuestStarterTypeToString[QuestStarterType] .. ", ID: " .. QuestStarterID
			if (QuestStarter[QS_RELATED_EVENT_IDX] ~= 0) then StringToShow = StringToShow .. ", Связан с текущим событием" end
			StringToShow = StringToShow .. ")"
			Tooltip:AddLine(StringToShow, 1, 1, 0)
			Tooltip:AddLine("Доступные у нее/него квесты:", 1, 1, 0)
			for j = 1, #AvailableQuests do
				local QuestID = AvailableQuests[j]
				local QuestName = Quests[QuestID][Q_RU_NAME_IDX]
				StringToShow = QuestName .. ", ID: " .. QuestID
				if (Quests[QuestID][Q_RELATED_EVENT_IDX] ~= 0) then 
					StringToShow = StringToShow .. ", Связан с текущим событием"
				end
				Tooltip:AddLine(StringToShow, 1, 1, 0)
			end
		end
		if (i < #QuestStarters) then
			Tooltip:AddLine("", 1, 1, 0)
		end
	end
	Tooltip:Show()
end

function WisdomKeeper:OnLeave(MapFile, Hash)
	self.Tooltip:Hide()
end


---------------------------------------------

