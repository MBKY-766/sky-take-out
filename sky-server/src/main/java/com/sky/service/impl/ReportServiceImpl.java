package com.sky.service.impl;

import com.sky.dto.GoodsSalesDTO;
import com.sky.entity.Orders;
import com.sky.mapper.OrderMapper;
import com.sky.mapper.UserMapper;
import com.sky.service.ReportService;
import com.sky.service.WorkspaceService;
import com.sky.vo.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ReportServiceImpl implements ReportService {

    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private WorkspaceService workspaceService;

    /**
     * 指定时间营业额统计
     *
     * @param begin
     * @param end
     * @return
     */
    @Override
    public TurnoverReportVO getTurnoverStatistics(LocalDate begin, LocalDate end) {
        //当前集合存放从begin到end到所有日期
        List<LocalDate> dateList = getLocalDateList(begin, end);
        List<Double> turnoverList = new ArrayList<>();
        for (LocalDate date : dateList) {
            //查询date日期对应的营业额数据，营业额是指：状态为“已完成”的订单金额总和
            LocalDateTime beginTime = LocalDateTime.of(date, LocalTime.MIN);//00:00:00
            LocalDateTime endTime = LocalDateTime.of(date, LocalTime.MAX);//23:59:59
            Map map = new HashMap();
            map.put("begin", beginTime);
            map.put("end", endTime);
            map.put("status", Orders.COMPLETED);
            Double turnover = orderMapper.sumByMap(map);
            turnover = turnover == null ? 0.0 : turnover;
            turnoverList.add(turnover);
        }

        return TurnoverReportVO.builder()
                .dateList(StringUtils.join(dateList, ","))
                .turnoverList(StringUtils.join(turnoverList, ","))
                .build();
    }

    /**
     * 用户数据统计
     *
     * @param begin
     * @param end
     * @return
     */
    @Override
    public UserReportVO getUserStatistics(LocalDate begin, LocalDate end) {
        //当前集合存放从begin到end到所有日期
        List<LocalDate> dateList = getLocalDateList(begin, end);
        //遍历日期列表，统计每天的用户数据
        List<Integer> totalUserList = new ArrayList<>();
        List<Integer> newUserList = new ArrayList<>();
        for (LocalDate date : dateList) {
            //查询date日期对应的用户数据
            LocalDateTime beginTime = LocalDateTime.of(date, LocalTime.MIN);//00:00:00
            LocalDateTime endTime = LocalDateTime.of(date, LocalTime.MAX);//23:59:59
            Map map = new HashMap();
            map.put("end", endTime);
            //总用户数量
            Integer totalUser = userMapper.countByMap(map);
            //新增用户数量
            map.put("begin", beginTime);
            Integer newUser = userMapper.countByMap(map);
            totalUserList.add(totalUser);
            newUserList.add(newUser);

        }
        return UserReportVO.builder()
                .dateList(StringUtils.join(dateList, ","))
                .totalUserList(StringUtils.join(totalUserList, ","))
                .newUserList(StringUtils.join(newUserList, ","))
                .build();
    }

    /**
     * 订单数据统计
     *
     * @param begin
     * @param end
     * @return
     */
    @Override
    public OrderReportVO getOrdersStatistics(LocalDate begin, LocalDate end) {
        List<LocalDate> dateList = getLocalDateList(begin, end);
        //遍历日期列表，统计每天的订单数据
        List<Integer> orderCountList = new ArrayList<>();
        List<Integer> validOrderCountList = new ArrayList<>();
        for (LocalDate date : dateList) {
            //查询date日期对应的订单数据
            LocalDateTime beginTime = LocalDateTime.of(date, LocalTime.MIN);//00:00:00
            LocalDateTime endTime = LocalDateTime.of(date, LocalTime.MAX);//23:59:59
            Map map = new HashMap();
            map.put("begin", beginTime);
            map.put("end", endTime);
            //当天订单数
            Integer orderCount = orderMapper.countByMap(map);
            //有效订单数
            map.put("status", Orders.COMPLETED);
            Integer validOrderCount = orderMapper.countByMap(map);
            orderCountList.add(orderCount);
            validOrderCountList.add(validOrderCount);
        }

        //计算总订单数和有效订单数
        Integer totalOrderCount = orderCountList.stream().reduce(Integer::sum).get();
        Integer totalValidOrderCount = validOrderCountList.stream().reduce(Integer::sum).get();
        //计算订单完成率
        Double orderCompletionRate = 0.0;
        if (totalOrderCount > 0) {
            //保留一位小数
            orderCompletionRate = totalValidOrderCount.doubleValue() / totalOrderCount;
        }
        return OrderReportVO.builder()
                .dateList(StringUtils.join(dateList, ","))
                .orderCountList(StringUtils.join(orderCountList, ","))
                .validOrderCountList(StringUtils.join(validOrderCountList, ","))
                .totalOrderCount(totalOrderCount)
                .validOrderCount(totalValidOrderCount)
                .orderCompletionRate(orderCompletionRate)
                .build();
    }

    /**
     * 指定时间内的销量 top10 统计
     *
     * @param begin
     * @param end
     * @return
     */
    @Override
    public SalesTop10ReportVO getSalesTop10(LocalDate begin, LocalDate end) {
        LocalDateTime beginTime = LocalDateTime.of(begin, LocalTime.MIN);
        LocalDateTime endTime = LocalDateTime.of(end, LocalTime.MAX);
        List<GoodsSalesDTO> list = orderMapper.getSalesTop10(beginTime, endTime);
        return SalesTop10ReportVO.builder()
                .nameList(StringUtils.join(list.stream().map(GoodsSalesDTO::getName).collect(Collectors.toList()), ","))
                .numberList(StringUtils.join(list.stream().map(GoodsSalesDTO::getNumber).collect(Collectors.toList()), ","))
                .build();
    }

    /**
     * 导出运营数据报表
     *
     * @param response
     */
    @Override
    public void exportBusinessData(HttpServletResponse response) {
        //1.查询数据库，获取营业数据--查询最近30天营业数据
        LocalDate dateBegin = LocalDate.now().minusDays(30);
        LocalDate dateEnd = LocalDate.now().minusDays(1);

        //查询概览数据
        BusinessDataVO businessDataVO = workspaceService.getBusinessData(LocalDateTime.of(dateBegin, LocalTime.MIN), LocalDateTime.of(dateEnd, LocalTime.MAX));

        //2.通过poi将数据写入到excel文件中
        InputStream in = this.getClass().getClassLoader().getResourceAsStream("template/运营数据报表模板.xlsx");
        //基于模版文件创建一个新的excel文件
        try {
            XSSFWorkbook excel = new XSSFWorkbook(in);
            //获取表格sheet页
            XSSFSheet sheet = excel.getSheet("sheet1");
            //填充数据--时间
            sheet.getRow(1).getCell(1).setCellValue("时间：" + dateBegin + "至" + dateEnd);

            //获得第4行
            XSSFRow row = sheet.getRow(3);
            row.getCell(2).setCellValue(businessDataVO.getTurnover());
            row.getCell(4).setCellValue(businessDataVO.getOrderCompletionRate());
            row.getCell(6).setCellValue(businessDataVO.getNewUsers());

            //获得第5行
            row = sheet.getRow(4);
            row.getCell(2).setCellValue(businessDataVO.getValidOrderCount());
            row.getCell(4).setCellValue(businessDataVO.getUnitPrice());

            //填充明细数据
            for (int i = 0; i < 30; i++) {
                LocalDate date = dateBegin.plusDays(i);
                //查询当天营业数据
                BusinessDataVO businessDataVO1 = workspaceService.getBusinessData(LocalDateTime.of(date, LocalTime.MIN), LocalDateTime.of(date, LocalTime.MAX));
                //填充数据
                //获得某一行
                row = sheet.getRow(7 + i);
                row.getCell(1).setCellValue(date.toString());
                row.getCell(2).setCellValue(businessDataVO1.getTurnover());
                row.getCell(3).setCellValue(businessDataVO1.getValidOrderCount());
                row.getCell(4).setCellValue(businessDataVO1.getOrderCompletionRate());
                row.getCell(5).setCellValue(businessDataVO1.getUnitPrice());
                row.getCell(6).setCellValue(businessDataVO1.getNewUsers());
            }
            //3.通过输出流将Excel文件下载到客户端浏览器
            ServletOutputStream out = response.getOutputStream();
            excel.write(out);
            //释放资源
            out.close();
            excel.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    private static List<LocalDate> getLocalDateList(LocalDate begin, LocalDate end) {
        //当前集合存放从begin到end到所有日期
        List<LocalDate> dateList = new ArrayList<>();
        dateList.add(begin);
        while (!begin.equals(end)) {
            begin = begin.plusDays(1);
            dateList.add(begin);
        }
        return dateList;
    }

    /**
     *  根据条件统计订单数量
     * @param begin
     * @param end
     * @return
     */
    /*private Integer getOrderCount(LocalDateTime begin, LocalDateTime end, Integer status) {
        Map map = new HashMap();
        map.put("begin", begin);
        map.put("end", end);
        map.put("status", status);
        return orderMapper.countByMap(map);
    }*/
}
