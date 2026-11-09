FROM python:3.14.10-slim@sha256:9aaa474097d094040649f2162a627c0049b408f52138d980916788d80f04802f AS build
COPY --from=ghcr.io/astral-sh/uv:0.13.6@sha256:af22a52f5eafef3d78e551599d405c02df61919d18028f0cea8eda93bc9986d0 /uv /usr/local/bin/uv
WORKDIR /app
ENV UV_PYTHON_DOWNLOADS=never
COPY pyproject.toml uv.lock .python-version README.md ./
COPY src src
RUN uv sync --frozen --no-dev --no-editable

FROM python:3.14.10-slim@sha256:9aaa474097d094040649f2162a627c0049b408f52138d980916788d80f04802f
# pip is only for installing, and the virtual environment is built in the first stage. Removing it also removes the
# copies of urllib3, msgpack and pkg_resources it vendors, which carry known vulnerabilities of their own.
RUN python -m pip uninstall --yes --root-user-action ignore pip \
    && groupadd --system --gid 10001 mse && useradd --system --uid 10001 --gid mse --no-create-home mse
COPY --from=build /app/.venv /app/.venv
ENV PATH=/app/.venv/bin:$PATH
USER mse
ENTRYPOINT ["python", "-m", "mse_simulator"]
