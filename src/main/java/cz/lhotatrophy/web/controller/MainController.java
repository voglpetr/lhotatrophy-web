package cz.lhotatrophy.web.controller;

import cz.lhotatrophy.core.exceptions.UsernameOrEmailIsTakenException;
import cz.lhotatrophy.core.exceptions.WeakPasswordException;
import cz.lhotatrophy.core.service.TeamListingQuery;
import cz.lhotatrophy.core.service.TeamService;
import cz.lhotatrophy.core.service.UserService;
import cz.lhotatrophy.persist.entity.Team;
import cz.lhotatrophy.persist.entity.TeamMember;
import cz.lhotatrophy.persist.entity.User;
import cz.lhotatrophy.web.form.TeamRegistrationForm;
import cz.lhotatrophy.web.form.TeamSettingsForm;
import cz.lhotatrophy.web.form.UserPasswordRecoveryForm;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.mutable.Mutable;
import org.apache.commons.lang3.mutable.MutableObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

/**
 *
 * @author Petr Vogl
 */
@Controller
@Log4j2
public class MainController {

	@Autowired
	private transient UserService userService;
	@Autowired
	private transient TeamService teamService;

	/**
	 * Homepage
	 */
	@GetMapping("/")
	public String index(
			final TeamRegistrationForm teamRegistrationForm,
			final Model model
	) {
		log.info("HOMEPAGE");

		initModel(model);
		return "public/index";
	}

	/**
	 * Login
	 */
	@GetMapping("/login")
	public String login(
			final Model model
	) {
		log.info("LOGIN");

		initModel(model);
		return "public/login";
	}

	/**
	 * Registration
	 */
	@GetMapping("/register")
	public String getRegistration(
			final TeamRegistrationForm teamRegistrationForm
	) {
		log.info("TEAM REGISTRATION (GET)");
		return "public/register";
	}

	/**
	 * Registration
	 */
	@PostMapping("/register")
	public String postRegistration(
			@Valid final TeamRegistrationForm teamRegistrationForm,
			final BindingResult bindingResult,
			final HttpServletRequest request,
			final Model model
	) {
		log.info("TEAM REGISTRATION (POST)");
		final String email = teamRegistrationForm.getEmail().trim().toLowerCase();
		final String passwd = teamRegistrationForm.getPassword().trim();
		final String teamName = teamRegistrationForm.getTeamName().trim();
		// user credentials validation
		if (userService.getUserByEmail(email).isPresent()) {
			bindingResult.rejectValue(
					"email",
					"Exists",
					"T??m s touto e-mailovou adresou je u?? registrov??n.");
		}
		// Password lenght is validated by form-validation
		//	if (!userService.checkPasswordStrength(passwd)) {
		//		bindingResult.rejectValue(
		//				"password",
		//				"Strength",
		//				"Heslo mus?? m??t nejm??n?? 6 znak??.");
		//	}
		// team name validation
		if (teamService.getTeamByName(teamName).isPresent()) {
			bindingResult.rejectValue(
					"teamName",
					"Exists",
					"T??m s t??mto n??zvem je u?? registrov??n.");
		}
		if (bindingResult.hasErrors()) {
			// log errors
			//	final List<FieldError> fieldErrors = bindingResult.getFieldErrors();
			//	if (fieldErrors != null) {
			//		fieldErrors.stream()
			//				.forEach((error) -> {
			//					log.info("FieldError: {} [ {} \"{}\" ]", error.getField(), error.getCode(), error.getDefaultMessage());
			//				});
			//	}
			return "public/register";
		}
		// save new team
		final Team team;
		try {
			team = userService.runInTransaction(() -> {
				final User user_;
				{
					user_ = userService.registerNewUser(email, passwd);
					user_.addProperty("zaplaceno", false);
				}
				return teamService.registerNewTeam(teamName, user_);
			});
		} catch (final Exception ex) {
			if (ex instanceof UsernameOrEmailIsTakenException || ex instanceof WeakPasswordException) {
				bindingResult.reject(
						"GlobalError",
						ex.getMessage());
			} else {
				// something went terribly wrong
				log.error("User registration failed.", ex);
				bindingResult.reject(
						"GlobalError",
						"N??co se pokazilo, zkus se registrovat znovu.");
			}
			return "public/register";
		}
		// autologin and redirect
		userService.autologin(team.getOwner());
		request.getSession().setAttribute("TeamRegistrationSuccess", true);
		return "redirect:/muj-tym";
	}

	/**
	 * Password recovery
	 */
	@GetMapping("/zmena-hesla/{userId}/{token}")
	public String passwordRecovery(
			@PathVariable final Long userId,
			@PathVariable final String token,
			final UserPasswordRecoveryForm userPasswordRecoveryForm
	) {
		log.info("PASSWORD RECOVERY (GET)");
		// user must exist
		final User user = userService.getUserByIdFromCache(userId).get();
		// token must be valid
		final boolean isTokenValid = user.getProperty("passwdRecoveryToken")
				.map(Object::toString)
				.filter(t -> t.equals(token))
				.isPresent();
		if (!isTokenValid) {
			throw new RuntimeException("Invalid password recovery token.");
		}
		userPasswordRecoveryForm.setId(userId);
		userPasswordRecoveryForm.setToken(token);
		return "public/password-recovery";
	}

	/**
	 * Password recovery
	 */
	@PostMapping("/zmena-hesla")
	public String postPasswordRecovery(
			@Valid final UserPasswordRecoveryForm userPasswordRecoveryForm,
			final BindingResult bindingResult
	) {
		log.info("PASSWORD RECOVERY (POST)");
		final Long userId = userPasswordRecoveryForm.getId();
		final String token = userPasswordRecoveryForm.getToken();
		// user must exist
		final Optional<User> optUser = userService.getUserByIdFromCache(userId);
		// token must be valid
		final boolean isTokenValid = optUser
				.flatMap(user -> user.getProperty("passwdRecoveryToken"))
				.map(Object::toString)
				.filter(t -> t.equals(token))
				.isPresent();
		if (optUser.isEmpty() || !isTokenValid) {
			bindingResult.reject(
					"GlobalError",
					"Odkaz pro zm??nu hesla u?? nen?? platn??.");
		}
		if (bindingResult.hasErrors()) {
			return "public/password-recovery";
		}
		// set new password
		userService.getUserById(userId)
				.ifPresent(u -> {
					userService.encodeAndSetUserPassword(u, userPasswordRecoveryForm.getPassword());
					u.removeProperty("passwdRecoveryToken");
					userService.updateUser(u);
				});
		return "redirect:/muj-tym";
	}

	/**
	 * Team
	 */
	@GetMapping("/muj-tym")
	public String myTeam(
			final TeamSettingsForm teamSettingsForm,
			final Model model
	) {
		log.info("TEAM");

		final Mutable<List<TeamMember>> members = new MutableObject();

		userService.getLoggedInUser().ifPresent(user -> {
			if (hasTeamAssigned(user)) {
				final Long teamId = user.getTeam().getId();
				teamService.getTeamByIdFromCache(teamId).ifPresent(team -> {
					List<TeamMember> members_ = team.getMembersOrdered();
					if (members_ == null || members_.isEmpty()) {
						members_ = new ArrayList(5);
						for (int i = 1; i <= 5; i++) {
							members_.add(new TeamMember());
						}
					}
					members.setValue(members_);
				});
			}
		});

		// data to fill the form
		model.addAttribute("teamSettings", members.getValue());

		initModel(model);
		return "public/my-team";
	}

	/**
	 * Team settings edit
	 */
	@PostMapping("/muj-tym")
	public String editMyTeam(
			final TeamSettingsForm teamSettingsForm,
			final HttpServletRequest request,
			final Model model
	) {
		log.info("TEAM EDIT (POST)");

		final Mutable<Set<TeamMember>> members = new MutableObject();
		userService.getLoggedInUser().ifPresent(user -> {
			userService.runInTransaction(() -> {
				final Long teamId = user.getTeam().getId();
				teamService.getTeamById(teamId).ifPresent(team -> {
					final Set<TeamMember> members_ = teamSettingsForm.getTeamMembers();
					members.setValue(members_);
					team.updateMembers(members_);
					teamService.updateTeam(team);
				});
			});
		});

		// data to fill the form
		model.addAttribute("teamSettings", members.getValue());
		request.getSession().setAttribute("TeamUpdateSuccess", true);
		initModel(model);
		return "public/my-team";
	}

	/**
	 * Team listing
	 */
	@GetMapping("/prihlasene-tymy")
	public String teamList(final Model model) {
		log.info("TEAM LISTING");

		model.addAttribute("allTeamsList", teamService.getTeamListing(new TeamListingQuery()));

		initModel(model);
		return "public/team-list";
	}

	/**
	 * Returns {@code true} only if the given {@code user} is not {@code null}
	 * and has a team assigned.
	 *
	 * @param user The user entity
	 * @return {@code true} if a team is assigned
	 */
	private boolean hasTeamAssigned(final User user) {
		return user != null
				&& user.getId() != null
				&& userService.getUserByIdFromCache(user.getId())
						.map(User::getTeam)
						.isPresent();
	}

	/**
	 * Initialize the model.
	 *
	 * @param model
	 */
	private void initModel(final Model model) {

		// FIXME - udelat lepe
		final Map<String, Object> appConfig;
		{
			appConfig = new HashMap<>();
			// TOTO - system property
			appConfig.put("teamRegistrationLimit", 50L);
			model.addAttribute("appConfig", appConfig);
		}

		final Function<Team, User> getOwner = (t) -> {
			final Long userId = t.getOwner().getId();
			return userService.getUserByIdFromCache(userId).orElse(null);
		};

		// logged in user and team
		final Optional<User> optUser = userService.getLoggedInUser()
				.map(User::getId)
				.flatMap(userId -> userService.getUserByIdFromCache(userId));
		final Optional<Team> optTeam = optUser
				.map(User::getTeam)
				.map(Team::getId)
				.flatMap(teamId -> teamService.getTeamByIdFromCache(teamId));
		// set data
		model.addAttribute("user", optUser.orElse(null));
		model.addAttribute("team", optTeam.orElse(null));
		model.addAttribute("teamMembers", optTeam.map(Team::getMembers).orElse(Collections.emptySet()));
		// set methods
		model.addAttribute("getOwner", getOwner);
	}
}
