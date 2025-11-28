package com.github.jelatinone.scholarfind.models;

import java.net.URL;
import java.time.LocalDate;
import java.util.Collection;

public record AnnotateDocument(
		String scholarshipTitle,
		String organizationName,

		URL domain,

		Double award,

		LocalDate open,
		LocalDate close,

		Collection<PursuedDegreeLevel> pursued,
		Collection<EducationLevel> education,

		Collection<Supplement> supplements,
		Collection<String> requirements) {

	public enum Supplement {
		ESSAY_REQUIRED,
		NEEDS_BASED,
		MERIT_BASED,
	}

	public enum PursuedDegreeLevel {

		NO_DEGREE,
		CERTIFICATE,
		DIPLOMA,
		TRADE_CERTIFICATION,
		VOCATIONAL_CERTIFICATION,

		ASSOCIATES,
		BACHELORS,

		MASTERS,
		DOCTORATE,
		PHD,
		EDD,
		DBA,

		JD,
		MD,
		DO,
		DDS,
		DMD,
		DVM,
		PHARMD,
		DNP,
		PA_MASTERS,

		MBA,
		MENG,
		MSC,
		MA,
		MFA,
		MPA,
		MPH,
		MSW,
		MSN,

		POSTGRADUATE_CERTIFICATE,
		POST_MASTERS_CERTIFICATE,
		POSTDOCTORAL,

		ANY_DEGREE,
		UNDERGRADUATE_LEVEL,
		GRADUATE_LEVEL,
		PROFESSIONAL_LEVEL
	}

	public enum EducationLevel {

		ELEMENTARY_SCHOOL,
		MIDDLE_SCHOOL,
		JUNIOR_HIGH,
		HIGH_SCHOOL_FRESHMAN,
		HIGH_SCHOOL_SOPHOMORE,
		HIGH_SCHOOL_JUNIOR,
		HIGH_SCHOOL_SENIOR,
		HIGH_SCHOOL_GRADUATE,
		GED,

		UNDERGRADUATE,
		COLLEGE_FRESHMAN,
		COLLEGE_SOPHOMORE,
		COLLEGE_JUNIOR,
		COLLEGE_SENIOR,
		ASSOCIATE_DEGREE_STUDENT,
		BACHELOR_DEGREE_STUDENT,

		GRADUATE,
		MASTERS_STUDENT,
		DOCTORAL_STUDENT,
		PHD_CANDIDATE,

		LAW_SCHOOL,
		MEDICAL_SCHOOL,
		DENTAL_SCHOOL,
		BUSINESS_SCHOOL,
		ENGINEERING_SCHOOL,
		NURSING_SCHOOL,
		VOCATIONAL_PROGRAM,
		TRADE_SCHOOL,
		CERTIFICATE_PROGRAM,

		POSTGRADUATE,
		POSTDOCTORAL,

		ANY_STUDENT,
		CURRENT_STUDENT,
		FUTURE_STUDENT,
		RETURNING_STUDENT,
		ADULT_LEARNER,
		CONTINUING_EDUCATION;
	}

}
