package com.hotplace.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.common.dto.Response;
import com.common.exception.CustomException;
import com.common.service.S3ImageService;
import com.common.util.Directory;
import com.hotplace.entity.HotPlaceEntity;
import com.hotplace.entity.HotPlaceInfoEntity;
import com.hotplace.entity.HotPlaceRecommendEntity;
import com.hotplace.mapper.HotplaceMapper;
import com.hotplace.util.WeightCalculator;
import com.hotplace.vo.HotplaceRequest.HotPlace;
import com.hotplace.vo.HotplaceResponse;
import com.hotplace.vo.HotplaceResponse.HotPlaceDetail;
import com.hotplace.vo.HotplaceResponse.HotPlaceInfo;
import com.hotplace.vo.HotplaceResponse.HotPlacePageInfo;
import com.user.entity.User;
import com.user.mapper.UserMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class HotplaceServiceImpl implements HotplaceService {

	private final HotplaceMapper hotplaceMapper;
	private final UserMapper userMapper;
	private final S3ImageService imageService;
	private final WeightCalculator weightCalculator;
	private final Response response;

	@Transactional
	@Override
	public ResponseEntity<?> addNewHotPlace(List<MultipartFile> images, HotPlace hotPlace, String userEmail) {
		try {
			User authUser = userMapper.selectByEmail(userEmail);
			
			if(hotPlace.visitDate().isAfter(LocalDate.now())) {
				return response.fail("잘못된 방문 날자입니다.", HttpStatus.BAD_REQUEST);
			}
			
			HotPlaceEntity entity = HotPlaceEntity.from(hotPlace);
			
			Map<String, Object> paramMap = new HashMap<>();
			paramMap.put("userId", authUser.getId());
			paramMap.put("entity", entity);
			
			hotplaceMapper.insertHotPlace(paramMap);
			
			int hotPlaceId = entity.getId();

			log.info("" + images.size());
			List<String> imageAddress = new ArrayList<>();
			for (MultipartFile image : images) {
				String url = imageService.upload(image, Directory.HOTPLACE);
				
				if(!url.equals("")) {
					imageAddress.add(url);
				}
			}
			
			if(imageAddress.size() >= 1) {
				paramMap.clear();
				paramMap.put("hid", hotPlaceId);
				paramMap.put("images", imageAddress);
				
				hotplaceMapper.insertHotPlaceImages(paramMap);
			}
		} catch (CustomException e) {
			return response.fail(e.getMessage(), e.getStatus());
		} catch (Exception e) {
			return response.fail(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
		}

		return response.success("핫 플레이스 추가 성공");
	}

	@Transactional(readOnly = true)
	@Override
	public ResponseEntity<?> getHotPlaceInfo(int offset) {
		List<HotPlaceInfo> result = hotplaceMapper.selectAllHotPlace(offset)
				.stream()
				.map(HotPlaceInfo::from)
				.collect(Collectors.toList());
		
		int totalCount = hotplaceMapper.countAllHotPlace();
		int currentOffset = offset;
		
		HotPlacePageInfo page = new HotPlacePageInfo(currentOffset, result, totalCount);
		
		return response.success(page, "hotplcae info 정보 불러오기 성공", HttpStatus.OK);
	}

	@Transactional(readOnly = true)
	@Override
	public ResponseEntity<?> getHotPlaceDeatil(String hotPlaceId) {
		HotPlaceInfoEntity entity = null;
		List<String> imageList = null;
		
		try {
			entity = hotplaceMapper.selectHotplaceInfoByHotPlaceId(hotPlaceId);
			imageList = hotplaceMapper.selectHotplaceImageByHotPlaceId(hotPlaceId);
		} catch (Exception e) {
			response.fail(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
		}
		
		HotPlaceDetail detail = HotPlaceDetail.from(entity, imageList);
		
		return response.success(detail, "상세 데이터 조회 성공", HttpStatus.OK);
	}

	@Transactional
	@Override
	public ResponseEntity<?> recommendHotPlace(String hotPlaceId, String userEmail) {
		User user = userMapper.selectByEmail(userEmail);
		HotPlaceRecommendEntity entity = null;
		int valid = 0;
		
		try {
			Map<String, Object> paramMap = new HashMap<>();
			paramMap.put("hid", hotPlaceId);
			paramMap.put("uid", user.getId());
			
			entity = hotplaceMapper.getRecommendRecord(paramMap);
			System.out.println(entity);
			
			if(entity == null) {
				HotPlaceRecommendEntity newEntity = HotPlaceRecommendEntity.builder()
						.uid(user.getId())
						.valid(1)
						.hid(Integer.parseInt(hotPlaceId))
						.build();
				
				hotplaceMapper.insertRecommendRecord(newEntity);
				valid = 1;
			} else {
				valid = entity.getValid();
				paramMap.put("valid", valid ^ 1);
				
				hotplaceMapper.updateRecommendRecord(paramMap);
				valid ^= 1;
			}
			
		} catch(Exception e) {
			response.fail(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
		}
		
		return response.success(valid == 0 ? "추천 해제 완료" : "추천 완료");
	}

	@Transactional(readOnly = true)
	@Override
	public ResponseEntity<?> getRecommendTop() {
		List<HotPlaceInfoEntity> result = hotplaceMapper.selectAllHotPlaceInfo();
		PriorityQueue<HotPlaceInfoEntity> topRecommend = new PriorityQueue<>();
		
		for(HotPlaceInfoEntity entity : result) {
			List<HotPlaceRecommendEntity> recommendList = hotplaceMapper.selectRecommendByHid(entity.getId());
			double weight = weightCalculator.getWeight(recommendList
					.stream()
					.filter(e -> e.getValid() == 1)
					.collect(Collectors.toList()));
			
			entity.setRecommendWeight(weight);
			topRecommend.offer(entity);
		}
		
		List<HotPlaceInfo> resultList = new ArrayList<>();
		
		for(int i = 0; i < Math.min(topRecommend.size(), 5); i++) {
			HotPlaceInfoEntity entity = topRecommend.poll();
			resultList.add(HotPlaceInfo.from(entity));
		}
		
		return response.success(resultList, "추천 탑5 조회 성공", HttpStatus.OK);
	}

}