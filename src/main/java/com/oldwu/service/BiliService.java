package com.oldwu.service;

import com.alibaba.fastjson.JSONObject;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.misec.apiquery.ApiList;
import com.misec.login.Verify;
import com.misec.pojo.userinfobean.Data;
import com.misec.task.TaskInfoHolder;
import com.misec.utils.HelpUtil;
import com.oldwu.dao.AutoBilibiliDao;
import com.oldwu.dao.AutoLogDao;
import com.oldwu.dao.BiliUserDao;
import com.oldwu.dao.UserDao;
import com.oldwu.entity.AutoBilibili;
import com.oldwu.entity.AutoLog;
import com.oldwu.entity.BiliPlan;
import com.oldwu.entity.BiliUser;
import com.oldwu.util.HttpUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

import static com.misec.task.TaskInfoHolder.STATUS_CODE_STR;
import static com.misec.task.TaskInfoHolder.userInfo;

@Service
public class BiliService {
    private static final String qrcodeUrl = "http://passport.bilibili.com/qrcode/getLoginUrl";
    private static final String qrcodeStatusUrl = "http://passport.bilibili.com/qrcode/getLoginInfo";

    @Autowired
    private AutoBilibiliDao autoBilibiliDao;

    @Autowired
    private BiliUserDao biliUserDao;

    @Autowired
    private AutoLogDao autoLogDao;

    @Autowired
    private UserDao userDao;

    public Map<String, Object> getQrcodeStatus(String oauthKey) {
        Map<String, String> headers = new HashMap<>();
        Map<String, Object> result = new HashMap<>();
        String body = "oauthKey=" + oauthKey;
        headers.put("Accept-Language", "zh-cn");
        headers.put("Connection", "keep-alive");
        headers.put("Content-Type", "application/x-www-form-urlencoded");
        try {
            HttpResponse httpResponse = HttpUtils.doPost(qrcodeStatusUrl, null, headers, null, body);
            JSONObject json = HttpUtils.getJson(httpResponse);
            if (json == null || json.getBoolean("status") == null) {
                result.put("code", "-100");
                result.put("msg", "?????????????????????");
                return result;
            }
            Boolean status = json.getBoolean("status");
            if (!status) {
                Integer data = json.getInteger("data");
                String message = json.getString("message");
                result.put("code", data);
                result.put("msg", message);
                return result;
            } else {
                //????????????
                Map<String, String> cookies = HttpUtils.getCookies(httpResponse);
                result.put("code", 200);
                result.put("msg", "??????????????????????????????");
                result.put("dedeuserid", cookies.get("DedeUserID"));
                result.put("sessdate", cookies.get("SESSDATA"));
                result.put("bilijct", cookies.get("bili_jct"));
                return result;
            }
        } catch (Exception e) {
            e.printStackTrace();
            result.put("code", "-101");
            result.put("msg", "?????????????????????" + e);
            return result;
        }
    }

    public String getQrcodeAuth() {
        try {
            HttpResponse httpResponse = HttpUtils.doGet(qrcodeUrl, null, HttpUtils.getHeaders(), null);
            JSONObject json = HttpUtils.getJson(httpResponse);
            if (json != null && json.getInteger("code") == 0) {
                return json.getJSONObject("data").getString("oauthKey");
            } else {
                return "????????????????????????";
            }
        } catch (Exception e) {
            return "????????????????????????";
        }
    }

    public List<BiliPlan> getAllPlan() {
        List<BiliPlan> newPlans = new ArrayList<>();
        for (BiliPlan biliPlan : biliUserDao.selectAll()) {
            biliPlan.setBiliName(HelpUtil.userNameEncode(biliPlan.getBiliName()));
            newPlans.add(biliPlan);
        }
        return newPlans;
    }

    public AutoBilibili getMyEditPlan(AutoBilibili autoBilibili1) {
        AutoBilibili autoBilibili = autoBilibiliDao.selectByPrimaryKey(autoBilibili1.getId());
        if (autoBilibili == null || autoBilibili.getId() == null) {
            return null;
        }
        //???????????????
        String role = userDao.getRole(autoBilibili1.getUserid());
        if (!autoBilibili.getUserid().equals(autoBilibili1.getUserid()) && !role.equals("ROLE_ADMIN")) {
            return null;
        }
        return autoBilibili;
    }

    public List<BiliPlan> getMyPlan(Integer userid) {
        return biliUserDao.selectMine(userid);
    }

    public Map<String, String> addBiliPlan(AutoBilibili autoBilibili) {
        Map<String, String> map = new HashMap<>();
        Map<String, Object> stringObjectMap = checkForm(autoBilibili, false);
        if (!(boolean) stringObjectMap.get("flag")) {
            map.put("code", "-1");
            map.put("msg", (String) stringObjectMap.get("msg"));
            return map;
        }
        //??????????????????????????????cookie?????????????????????????????????
        Map<String, String> usercheck = checkUser(autoBilibili);
        if (usercheck.get("flag").equals("false")) {
            map.put("code", "-1");
            map.put("msg", usercheck.get("msg"));
            return map;
        }
        //??????????????????
        map.put("code", "200");
        map.put("msg", usercheck.get("msg"));
        return map;
    }

    /**
     * ??????????????????????????????????????????????????????msg
     *
     * @param autoBilibili
     * @return
     */
    public Map<String, String> checkUser(AutoBilibili autoBilibili) {
        Map<String, String> map = new HashMap<>();
        String requestPram = "";
        Verify.verifyInit(autoBilibili.getDedeuserid(), autoBilibili.getSessdata(), autoBilibili.getBiliJct());
        JsonObject userJson = com.misec.utils.HttpUtils.doGet(ApiList.LOGIN + requestPram);
        if (userJson == null) {
            map.put("flag", "false");
            map.put("msg", "????????????????????????????????????412???????????????config.json?????????UA???412???????????????????????????????????????????????????");
            return map;
        } else {
            userJson = com.misec.utils.HttpUtils.doGet(ApiList.LOGIN);
            //??????Cookies????????????
            if (userJson.get(STATUS_CODE_STR).getAsInt() == 0
                    && userJson.get("data").getAsJsonObject().get("isLogin").getAsBoolean()) {
                userInfo = new Gson().fromJson(userJson
                        .getAsJsonObject("data"), Data.class);
//                log.info("Cookies?????????????????????");
            } else {
//                log.debug(String.valueOf(userJson));
                map.put("flag", "false");
                map.put("s", "cookie");
                map.put("msg", "Cookies???????????????,???????????????????????????DEDEUSERID SESSDATA BILI_JCT?????????????????????????????????");
                return map;
            }
        }
        String s = userInfo.getUname();
        long mid = userInfo.getMid();
        //???????????????????????????????????????
        BiliUser biliUser = biliUserDao.selectByMid(mid);
        if (biliUser == null || biliUser.getId() == null) {
            //????????????????????????????????????????????????????????????biliuser??????bili?????????
            autoBilibiliDao.insertSelective(autoBilibili);
            if (autoBilibili.getId() <= 0) {
                map.put("flag", "false");
                map.put("msg", "?????????????????????????????????????????????");
                return map;
            }
            boolean b = updateUserInfo(autoBilibili, userInfo, false);
            if (!b) {
                map.put("flag", "false");
                map.put("msg", "?????????????????????????????????????????????");
                return map;
            }
            map.put("msg", "???????????????????????????" + s);
            map.put("flag", "true");
            return map;
        } else {
            //??????????????????
            Integer autoId = biliUser.getAutoId();
            autoBilibili.setId(autoId);
            int i = autoBilibiliDao.updateByPrimaryKeySelective(autoBilibili);
            if (i <= 0) {
                map.put("flag", "false");
                map.put("msg", "?????????????????????????????????????????????");
                return map;
            }
            updateUserInfo(autoBilibili, userInfo, true);
            map.put("msg", "?????????????????????????????????" + s);
            map.put("flag", "true");
            return map;
        }
    }

    public boolean updateUserInfo(AutoBilibili autoBilibili, Data userInfo, boolean update) {
        BiliUser biliUser1 = new BiliUser();
        biliUser1.setAutoId(autoBilibili.getId());
        biliUser1.setBiliCoin(userInfo.getMoney());
        biliUser1.setUid(userInfo.getMid());
        biliUser1.setBiliName(userInfo.getUname());
        biliUser1.setBiliLevel(userInfo.getLevel_info().getCurrent_level());
        biliUser1.setBiliExp((long) userInfo.getLevel_info().getCurrent_exp());
        biliUser1.setBiliUpexp((long) userInfo.getLevel_info().getNext_exp_asInt());
        biliUser1.setFaceImg(userInfo.getFace());
        biliUser1.setIsVip(TaskInfoHolder.queryVipStatusType() == 0 ? "false" : "true");
        biliUser1.setVipDueDate(new Date(userInfo.getVipDueDate()));
        if (!update) {
            //??????????????????
            return biliUserDao.insertSelective(biliUser1) > 0;
        } else {
            //update
            biliUser1.setAutoId(autoBilibili.getId());
            return biliUserDao.updateByAutoIdSelective(biliUser1) > 0;
        }
    }

    /**
     * ??????????????????????????????flag=false???msg=msg???
     *
     * @param autoBilibili
     * @return
     */
    public Map<String, Object> checkForm(AutoBilibili autoBilibili, boolean skipCookieCheck) {
        Map<String, Object> map = new HashMap<>();
        if (!skipCookieCheck) {
            String biliJct = autoBilibili.getBiliJct();
            String dedeuserid = autoBilibili.getDedeuserid();
            String sessdata = autoBilibili.getSessdata();
            if (StringUtils.isBlank(biliJct) || StringUtils.isBlank(dedeuserid) || StringUtils.isBlank(sessdata)) {
                map.put("flag", false);
                map.put("msg", "cookie???????????????????????????");
                return map;
            }
            if (StringUtils.isBlank(autoBilibili.getName())) {
                map.put("flag", false);
                map.put("msg", "????????????????????????");
                return map;
            }
        }
        Integer taskintervaltime = autoBilibili.getTaskintervaltime();
        if (taskintervaltime == null || taskintervaltime < 1 || taskintervaltime > 20) {
            autoBilibili.setTaskintervaltime(10);
        }
        Integer numberofcoins = autoBilibili.getNumberofcoins();
        if (numberofcoins == null || numberofcoins > 5 || numberofcoins < 0) {
            autoBilibili.setNumberofcoins(5);
        }
        Integer reservecoins = autoBilibili.getReservecoins();
        if (reservecoins == null || reservecoins < 0) {
            autoBilibili.setReservecoins(50);
        }
        Integer selectlike = autoBilibili.getSelectlike();
        if (selectlike == null || selectlike > 1 || selectlike < 0) {
            autoBilibili.setSelectlike(0);
        }
        String monthendautocharge = autoBilibili.getMonthendautocharge();
        if (StringUtils.isBlank(monthendautocharge) || !monthendautocharge.equals("true") && !monthendautocharge.equals("false")) {
            autoBilibili.setMonthendautocharge("true");
        }
        String uplive = autoBilibili.getUplive();
        if (StringUtils.isBlank(uplive)) {
            autoBilibili.setUplive("0");
        }
        String chargeforlove = autoBilibili.getChargeforlove();
        if (StringUtils.isBlank(chargeforlove) || !StringUtils.isNumeric(chargeforlove)) {
            autoBilibili.setChargeforlove("0");
        }
        String deviceplatform = autoBilibili.getDeviceplatform();
        if (StringUtils.isNumeric(deviceplatform) || !deviceplatform.equals("ios") && !deviceplatform.equals("android")) {
            autoBilibili.setDeviceplatform("ios");
        }
        Integer coinaddpriority = autoBilibili.getCoinaddpriority();
        if (coinaddpriority == null || coinaddpriority < 0 || coinaddpriority > 1) {
            autoBilibili.setCoinaddpriority(1);
        }
        String useragent = autoBilibili.getUseragent();
        if (StringUtils.isBlank(useragent)) {
            autoBilibili.setUseragent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/86.0.4240.198 Safari/537.36 Edg/86.0.622.69");
        }
        String skipdailytask = autoBilibili.getSkipdailytask();
        if (StringUtils.isBlank(skipdailytask) || !skipdailytask.equals("true") && !skipdailytask.equals("false")) {
            autoBilibili.setSkipdailytask("false");
        }
        String webhook = autoBilibili.getWebhook();
        if (StringUtils.isBlank(webhook)) {
            autoBilibili.setWebhook("");
        }
        String matchEnable = autoBilibili.getMatchEnable();
        if (StringUtils.isBlank(matchEnable) || !matchEnable.equals("true") && !matchEnable.equals("false")) {
            autoBilibili.setMatchEnable("false");
        }
        Integer predictnumberofcoins = autoBilibili.getMatchPredictnumberofcoins();
        if (predictnumberofcoins == null || predictnumberofcoins < 0) {
            autoBilibili.setMatchPredictnumberofcoins(10);
        }
        Integer minimumnumberofcoins = autoBilibili.getMatchMinimumnumberofcoins();
        if (minimumnumberofcoins == null || minimumnumberofcoins < 0) {
            autoBilibili.setMatchPredictnumberofcoins(200);
        }
        map.put("flag", true);
        map.put("msg", "check complete");
//        map.put("data", autoBilibili);
        return map;
    }

    public Map<String, Object> deleteBiliPlan(AutoBilibili autoBilibili) {
        Map<String, Object> map = new HashMap<>();
        //????????????id
        Integer userid = autoBilibili.getUserid();
        Integer autoid = autoBilibili.getId();
        if (autoid == null || autoid == 0) {
            map.put("code", -1);
            map.put("msg", "?????????????????????");
            return map;
        }
        List<BiliPlan> biliPlans = biliUserDao.selectMine(userid);
        boolean flag = false;
        for (BiliPlan biliPlan : biliPlans) {
            int autoId = biliPlan.getAutoId();
            if (autoId == autoid) {
                flag = true;
                break;
            }
        }
        if (userDao.getRole(userid).equals("ROLE_ADMIN")) {  //????????????
            flag = true;
        }
        if (!flag) {
            map.put("code", 403);
            map.put("msg", "????????????????????????????????????????????????");
            return map;
        }
        //??????????????????
        AutoLog autoLog = new AutoLog();
        autoLog.setUserid(autoBilibili.getUserid());
        autoLog.setAutoId(autoBilibili.getId());
        autoLog.setType("bilibili");
        try {
            autoLogDao.deleteByAutoId(autoLog);
            //????????????b???????????????
            biliUserDao.deleteByAutoId(autoid);
            //????????????????????????
            int i = autoBilibiliDao.deleteByPrimaryKey(autoid);
            if (i > 0) {
                map.put("code", 200);
                map.put("msg", "????????????");
                return map;
            }
        } catch (Exception e) {
            System.err.println(e.getMessage());
        }
        map.put("code", -1);
        map.put("msg", "???????????????");
        return map;
    }

    public Map<String, Object> editBiliPlan(AutoBilibili autoBilibili1) {
        Map<String, Object> map = new HashMap<>();
        AutoBilibili autoBilibili = autoBilibiliDao.selectByPrimaryKey(autoBilibili1.getId());
        if (autoBilibili == null || autoBilibili.getId() == null) {
            map.put("code", -1);
            map.put("msg", "???????????????");
            return map;
        }
        //???????????????
        String role = userDao.getRole(autoBilibili1.getUserid());
        if (!autoBilibili.getUserid().equals(autoBilibili1.getUserid()) && !role.equals("ROLE_ADMIN")) {
            map.put("code", 403);
            map.put("msg", "????????????????????????");
            return map;
        }
        checkForm(autoBilibili1, true);
        int i = autoBilibiliDao.updateByPrimaryKeySelective(autoBilibili1);
        if (i > 0) {
            map.put("code", 200);
            map.put("msg", "???????????????");
            return map;
        }
        map.put("code", 0);
        map.put("msg", "???????????????");
        return map;
    }
}
