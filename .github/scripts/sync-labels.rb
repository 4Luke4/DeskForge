#!/usr/bin/env ruby
# frozen_string_literal: true

require "json"
require "net/http"
require "yaml"

repository = ENV.fetch("GITHUB_REPOSITORY")
token = ENV.fetch("GITHUB_TOKEN")
labels = YAML.safe_load_file(File.join(ENV.fetch("GITHUB_WORKSPACE"), ".github/labels.yml"))

def request(repository, token, method, path, payload)
  uri = URI("https://api.github.com/repos/#{repository}/#{path}")
  request = method.new(uri)
  request["Accept"] = "application/vnd.github+json"
  request["Authorization"] = "Bearer #{token}"
  request["User-Agent"] = "DeskForge-label-synchronizer"
  request["X-GitHub-Api-Version"] = "2022-11-28"
  request.body = JSON.generate(payload)
  response = Net::HTTP.start(uri.hostname, uri.port, use_ssl: true) { |http| http.request(request) }
  [response.code.to_i, response.body]
end

labels.each do |label|
  payload = label.slice("name", "color", "description")
  code, body = request(repository, token, Net::HTTP::Post, "labels", payload)
  next if code == 201

  # Existing labels are updated in place so descriptions and colors do not drift.
  if code == 422
    escaped_name = URI.encode_www_form_component(label.fetch("name"))
    patch_code, patch_body = request(repository, token, Net::HTTP::Patch, "labels/#{escaped_name}", payload)
    next if patch_code == 200

    warn "Unable to update #{label.fetch('name')}: #{patch_code} #{patch_body}"
    exit 1
  end

  warn "Unable to create #{label.fetch('name')}: #{code} #{body}"
  exit 1
end
