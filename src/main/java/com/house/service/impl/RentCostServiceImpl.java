package com.house.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dingtalk.api.response.OapiMessageCorpconversationAsyncsendV2Response;
import com.house.common.Constant;
import com.house.exception.CheckException;
import com.house.model.*;
import com.house.model.vo.RentVO;
import com.house.service.DingTalkService;
import com.house.utils.DateUtils;
import com.house.mapper.RentCostMapper;
import com.house.model.vo.RentOwnerVO;
import com.house.model.vo.RentTenantVO;
import com.house.service.LeaseService;
import com.house.service.RentCostService;
import com.house.service.UserService;
import com.house.utils.Query;
import com.house.utils.UUIDGenerator;
import com.house.utils.UserContextHolder;
import com.taobao.api.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * ็ง้ๅ่กจ
 */
@Slf4j
@Service
@Transactional(propagation = Propagation.REQUIRED)
public class RentCostServiceImpl extends ServiceImpl<RentCostMapper, RentCost> implements RentCostService {

    @Autowired
    private UserService userService;
    @Autowired
    private DingTalkService dingTalkService;
    @Autowired
    private LeaseService leaseService;

    @Override
    public IPage<RentOwnerVO> getOwnerRentList(Map<String, Object> params, RentOwnerVO vo) {

        QueryWrapper<RentOwnerVO> queryWrapper = new QueryWrapper<RentOwnerVO>()
                .eq(ObjectUtil.isNotNull(vo.getRentCostStatus()), "rc.rent_cost_status", vo.getRentCostStatus())
                .eq("rc.is_delete", Constant.NO_DELETE)
                .eq("rc.user_id", UserContextHolder.getUserId())
                .eq(StringUtils.isNotBlank(vo.getLeaseId()), "l.lease_id", vo.getLeaseId())
                .like(StringUtils.isNotBlank(vo.getHouseOwnerName()), "ho.house_owner_name", vo.getHouseOwnerName())
                .like(StringUtils.isNotBlank(vo.getHouseName()), "h.house_name", vo.getHouseName())
                .isNotNull("h.house_id")
                .orderByAsc("rc.rent_cost_date");

        IPage<RentOwnerVO> iPage = baseMapper.getOwnerRentList(new Query<RentOwnerVO>().getPage(params), queryWrapper);
        List<RentOwnerVO> voList = iPage.getRecords();
        if (ObjectUtil.isNotEmpty(voList)) {
            LocalDate today = LocalDate.now();
            for (RentOwnerVO rentOwnerVO : voList) {
                LocalDate rentDate = Instant.ofEpochMilli(rentOwnerVO.getRentCostDate()).atZone(ZoneOffset.ofHours(8)).toLocalDate();
                Long diffDays = ChronoUnit.DAYS.between(today, rentDate);
                rentOwnerVO.setDiffDay(diffDays);
                rentOwnerVO.setDiffDayInfo(getDiffDayInfo(diffDays, rentOwnerVO.getRentCostStatus()));
            }
        }
        return iPage;
    }

    @Override
    public IPage<RentTenantVO> getTenantRentList(Map<String, Object> params, RentTenantVO vo) {
        QueryWrapper<RentTenantVO> queryWrapper = new QueryWrapper<RentTenantVO>()
                .eq(ObjectUtil.isNotNull(vo.getRentCostStatus()), "rc.rent_cost_status", vo.getRentCostStatus())
                .eq("rc.is_delete", Constant.NO_DELETE)
                .eq("rc.user_id", UserContextHolder.getUserId())
                .eq("le.lease_type", Constant.PERSON_TYPE_TENANT)
                .eq(StringUtils.isNotBlank(vo.getTenantLeaseId()), "le.lease_id", vo.getTenantLeaseId())
                .like(StringUtils.isNotBlank(vo.getHouseDetailName()),"hd.house_detail_name",vo.getHouseDetailName())
                .like(StringUtils.isNotBlank(vo.getTenantName()),"te.tenant_name",vo.getTenantName())
                .orderByAsc("rc.rent_cost_date");

        IPage<RentTenantVO> iPage = baseMapper.getTenantRentList(new Query<RentTenantVO>().getPage(params), queryWrapper);
        List<RentTenantVO> voList = iPage.getRecords();
        if (ObjectUtil.isNotEmpty(voList)) {
            LocalDate today = LocalDate.now();
            for (RentTenantVO rentTenantVO : voList) {
                LocalDate rentDate = DateUtils.getLocalDate(rentTenantVO.getRentCostDate());
                Long diffDays = ChronoUnit.DAYS.between(today, rentDate);
                rentTenantVO.setDiffDay(diffDays);
                rentTenantVO.setDiffDayInfo(getDiffDayInfo(diffDays, rentTenantVO.getRentCostStatus()));
            }
        }
        return iPage;
    }

    @Override
    public Boolean addOrUpdateRent(RentVO rentVO) {
        RentCost rentCost = BeanUtil.toBean(rentVO, RentCost.class);
        if (StringUtils.isNotBlank(rentVO.getRentCostId())) {
            RentCost rentCostOld = this.getById(rentVO.getRentCostId());
            if (rentCostOld.getRentCostStatus() != 1 && rentVO.getRentCostStatus() == 1) {
                rentCost.setRealTime(DateUtils.getNowTimeStamp());
            }
            // ๆดๆฐไปฃ็?
            return this.updateById(rentCost);
        } else {
            // ๆ?นๆฎๆฟๅฑๆ็ปID่ทๅ็ง็บฆID
            LambdaQueryWrapper<Lease> leaseLqw = Wrappers.<Lease>lambdaQuery()
                    .eq(Lease::getHouseDetailId, rentVO.getHouseName())
                    .eq(Lease::getLeaseType, Constant.PERSON_TYPE_TENANT)
                    .eq(Lease::getLeaseStatus, 0);
            Lease lease = leaseService.getOne(leaseLqw);
            if(ObjectUtil.isEmpty(lease)){
                throw new CheckException("ๆญคๆฟๆบๆชๆฅๅฐๅบ็งไฟกๆฏ๏ผ");
            }
            rentCost.setLeaseId(lease.getLeaseId());
            // ๆฐๅขไปฃ็?
            rentCost.setRentCostId(UUIDGenerator.getNextId(RentCost.PREFIX_RENT, baseMapper.getRentCostMaxNum()));
            if (rentVO.getRentCostStatus() == 1) {
                rentCost.setRealTime(DateUtils.getNowTimeStamp());
            }
            return this.save(rentCost);
        }
    }

    @Override
    public Boolean sendOwnerRentDingTalk() throws ApiException {
        // ่ทๅๆ้ๅ่กจ
        List<OwnerRemind> ownerRemindList = baseMapper.getOwnerRemind(DateUtils.getNowTimeStamp());
        List<SendLog> sendLogList = new ArrayList<>();
        if (ObjectUtil.isNotEmpty(ownerRemindList)) {
            Map<String, List<OwnerRemind>> detailsMap = ownerRemindList.stream()
                    .collect(Collectors.groupingBy(OwnerRemind::getUserId));
            Set<String> userIdSet = ownerRemindList.stream()
                    .map(OwnerRemind::getUserId)
                    .collect(Collectors.toSet());
            log.info("ๆ้ๅ่กจ็็จๆท้ๅuserIdSet:{}", userIdSet);
            // ่ทๅuserๅ่กจ
            List<UserInfo> userInfoList = userService.listByIds(userIdSet);
            if (ObjectUtil.isNotEmpty(userInfoList)) {
                // ่ทๅ้้UID
                for (UserInfo userInfo : userInfoList) {
                    if (StringUtils.isBlank(userInfo.getDingtalkUid())) {
                        String dingTalkUid = dingTalkService.getUidByPhone(userInfo.getUserPhone(), false);
                        if (StringUtils.isNotBlank(dingTalkUid)) {
                            userInfo.setDingtalkUid(dingTalkUid);
                            userService.updateById(userInfo);
                        }
                    }
                    List<OwnerRemind> tempList = detailsMap.get(userInfo.getUserId());
                    String content = this.buildMsg(tempList);
                    OapiMessageCorpconversationAsyncsendV2Response res = dingTalkService.sendWork(userInfo.getDingtalkUid(), content);
                    boolean isSuccess = "0".equals(res.getErrorCode());
                    SendLog sendLog = SendLog.builder()
                            .remark(isSuccess ? "ๅ้ๆๅ" : res.getErrmsg())
                            .userId(userInfo.getUserId())
                            .sendStatus(isSuccess ? "ๅ้ๆๅ" : "ๅ้ๅคฑ่ดฅ")
                            .type("ๅบไปๆฟไธ")
                            .sendTime(DateUtils.getNowTimeStamp())
                            .build();
                    sendLogList.add(sendLog);
                }
            } else {
                log.info("ๆช่ทๅๅฐ็จๆท็userIdSet:{}", userIdSet);
            }
        }
        // sendLogList ๆไฝ
        log.info("ๅ้sendLogList:{}", sendLogList);
        return true;
    }

    @Override
    public Boolean sendTenantRentDingTalk() throws ApiException {
        // ่ทๅๆ้ๅ่กจ
        List<TenantRemind> tenantReminds = baseMapper.getTenantRemind(DateUtils.getNowTimeStamp());
        List<SendLog> sendLogList = new ArrayList<>();
        if (ObjectUtil.isNotEmpty(tenantReminds)) {
            Map<String, List<TenantRemind>> detailsMap = tenantReminds.stream()
                    .collect(Collectors.groupingBy(TenantRemind::getUserId));
            Set<String> userIdSet = tenantReminds.stream()
                    .map(TenantRemind::getUserId)
                    .collect(Collectors.toSet());
            log.info("ๆ้ๅ่กจ็็จๆท้ๅuserIdSet:{}", userIdSet);
            // ่ทๅuserๅ่กจ
            List<UserInfo> userInfoList = userService.listByIds(userIdSet);
            if (ObjectUtil.isNotEmpty(userInfoList)) {
                // ่ทๅ้้UID
                for (UserInfo userInfo : userInfoList) {
                    if (StringUtils.isBlank(userInfo.getDingtalkUid())) {
                        String dingTalkUid = dingTalkService.getUidByPhone(userInfo.getUserPhone(), false);
                        if (StringUtils.isBlank(dingTalkUid)) {
                            continue;
                        }
                        userInfo.setDingtalkUid(dingTalkUid);
                        userService.updateById(userInfo);
                    }
                    List<TenantRemind> tempList = detailsMap.get(userInfo.getUserId());
                    String content = this.buildTenantMsg(tempList);
                    OapiMessageCorpconversationAsyncsendV2Response res = dingTalkService.sendWork(userInfo.getDingtalkUid(), content);
                    boolean isSuccess = "0".equals(res.getErrorCode());
                    SendLog sendLog = SendLog.builder()
                            .remark(isSuccess ? "ๅ้ๆๅ" : res.getErrmsg())
                            .userId(userInfo.getUserId())
                            .sendStatus(isSuccess ? "ๅ้ๆๅ" : "ๅ้ๅคฑ่ดฅ")
                            .sendTime(DateUtils.getNowTimeStamp())
                            .build();
                    sendLogList.add(sendLog);
                }
            } else {
                log.info("ๆช่ทๅๅฐ็จๆท็userIdSet:{}", userIdSet);
            }
        }
        // sendLogList ๆไฝ
        log.info("ๅ้sendLogList:{}", sendLogList);
        return true;
    }

    private String getDiffDayInfo(Long diffDays, Integer rentCostStatus) {
        if (rentCostStatus == 1) {
            return "ๅทฒไบค็ง";
        } else {
            if (diffDays > 0) {
                return String.format("่ฟๅฉ%sๅคฉ", diffDays);
            } else if (diffDays == 0) {
                return "ไปๅคฉๆถ็ง";
            } else {
                return String.format("ๅทฒ้พๆ%dๅคฉ", diffDays * -1);
            }
        }

    }

    private String buildTenantMsg(List<TenantRemind> reminds) {
        DateTimeFormatter dtf2 = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        StringBuilder stringBuilder = new StringBuilder("****ๅบๆถๆฟ็ง****\n");
        for (TenantRemind tenantRemind : reminds) {
            stringBuilder.append("-------------------\n");
            stringBuilder.append("ๆฟๆบ: ").append(tenantRemind.getHouseDetailName()).append("\n");
            stringBuilder.append("็ง้: ยฅ").append(tenantRemind.getRentCostMonth()).append("\n");
            String strDate2 = dtf2.format(DateUtils.getLocalDate(tenantRemind.getRentCostDate()));
            stringBuilder.append("ๅบๆถๆฅๆ: ").append(strDate2).append("\n");
            stringBuilder.append("็งๅฎข: ").append(tenantRemind.getTenantName()).append("\n");
            stringBuilder.append("็งๅฎขๆๆบๅท: ").append(tenantRemind.getTenantPhone()).append("\n");
            stringBuilder.append("-------------------\n");
        }
        return stringBuilder.toString();
    }

    private String buildMsg(List<OwnerRemind> reminds) {
        DateTimeFormatter dtf2 = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        StringBuilder stringBuilder = new StringBuilder("****ๅบไปๆฟ็ง****\n");
        for (OwnerRemind ownerRemind : reminds) {
            stringBuilder.append("-------------------\n");
            stringBuilder.append("ๆฟๆบ: ").append(ownerRemind.getHouseName()).append("\n");
            stringBuilder.append("็ง้: ยฅ").append(ownerRemind.getRentCostMonth()).append("\n");
            String strDate2 = dtf2.format(DateUtils.getLocalDate(ownerRemind.getRentCostDate()));
            stringBuilder.append("ๅบไปๆฅๆ: ").append(strDate2).append("\n");
            stringBuilder.append("ๆฟไธ: ").append(ownerRemind.getHouseOwnerName()).append("\n");
            stringBuilder.append("ๆฟไธๆๆบๅท: ").append(ownerRemind.getHouseOwnerPhone()).append("\n");
            stringBuilder.append("-------------------\n");
        }
        return stringBuilder.toString();
    }
}

